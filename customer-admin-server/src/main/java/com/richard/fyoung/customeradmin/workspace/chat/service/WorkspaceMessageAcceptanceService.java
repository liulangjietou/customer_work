package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatReceipt;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatTerminal;
import com.richard.fyoung.customeradmin.workspace.chat.entity.WorkspaceMessageScope;
import com.richard.fyoung.customeradmin.workspace.chat.store.WorkspaceMessageReceiptStore;
import com.richard.fyoung.customeradmin.workspace.runtime.WorkspaceRuntimeScope;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** 受理落库后才构建和订阅业务流；重复请求只查回执，绝不自动重放可能写业务的工具。 */
@Service
public class WorkspaceMessageAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMessageAcceptanceService.class);
    private static final String ERROR_RECEIPT_SAVE = "WORKSPACE-RECEIPT-SAVE-FAIL";
    private static final int ACCEPTANCE_MAX_ATTEMPTS = 3;
    private final WorkspaceMessageReceiptStore store;
    private final AdminTenantProperties tenantProperties;

    public WorkspaceMessageAcceptanceService(WorkspaceMessageReceiptStore store, AdminTenantProperties tenantProperties) {
        this.store = store;
        this.tenantProperties = tenantProperties;
    }

    /** 调用必须位于认证请求线程；旧客户端未传标识时保持原协议，新客户端启用持久幂等受理。 */
    public Flux<ChatStreamChunk> execute(String agentCode, long ownerId, String channel, ChatRequest request,
                                         Supplier<Flux<ChatStreamChunk>> sourceFactory) {
        if (request.clientMessageId() == null) {
            return sourceFactory.get();
        }
        WorkspaceMessageScope scope = scope(agentCode, request.sessionId(), ownerId, channel, request.clientMessageId());
        String fingerprint = fingerprint(request);
        boolean first = accept(scope, fingerprint);
        ChatReceipt receipt = store.require(scope, fingerprint);
        if (!first) {
            return replay(receipt);
        }
        // 在请求线程构造：运行身份、配额和调用元数据依赖该线程的认证上下文。
        Flux<ChatStreamChunk> source;
        try {
            source = sourceFactory.get();
        } catch (RuntimeException failure) {
            source = Flux.error(failure);
        }
        AtomicBoolean subscribed = new AtomicBoolean();
        AtomicBoolean finalized = new AtomicBoolean();
        Flux<ChatStreamChunk> execution = source.doOnNext(chunk -> {
            if (chunk.terminal() != null) {
                store.finish(scope, chunk.terminal());
                finalized.set(true);
            }
        }).onErrorResume(error -> {
            log.error("workspace accepted execution failed, errorCode={}, clientMessageId={}",
                ERROR_RECEIPT_SAVE, scope.clientMessageId(), error);
            ChatTerminal unknown = ChatTerminal.unknown(null);
            finishSafely(scope, unknown);
            finalized.set(true);
            return Flux.just(ChatStreamChunk.terminal(unknown));
        }).doFinally(signal -> {
            if (!finalized.get()) {
                finishSafely(scope, ChatTerminal.unknown(null));
            }
        });
        // 同一个冷 Flux 被二次订阅也不能再次执行；跨请求由数据库唯一键决胜。
        return Flux.defer(() -> subscribed.compareAndSet(false, true)
            ? Flux.concat(Flux.just(ChatStreamChunk.accepted(receipt)), execution)
            : replay(store.require(scope, fingerprint)));
    }

    /** 仅核对当前调用者自己发起的消息，不因 TENANT 数据范围扩大而暴露其他人的受理记录。 */
    public ChatReceipt receipt(String agentCode, String sessionId, long ownerId, String channel, String clientMessageId) {
        return store.find(scope(agentCode, sessionId, ownerId, channel, clientMessageId))
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "消息受理记录不存在"));
    }

    /**
     * 唯一键竞争也可能选中当前插入为死锁牺牲者。必须在 Store 的独立事务回滚后再重试，
     * 不能在同一事务内循环，也不能重试包含模型或业务工具的执行流。
     */
    private boolean accept(WorkspaceMessageScope scope, String fingerprint) {
        long acceptedAt = System.currentTimeMillis();
        int attempt = 1;
        while (true) {
            try {
                return store.accept(scope, fingerprint, acceptedAt);
            } catch (CannotAcquireLockException contention) {
                if (attempt++ >= ACCEPTANCE_MAX_ATTEMPTS) {
                    throw contention;
                }
            }
        }
    }

    private WorkspaceMessageScope scope(String agentCode, String sessionId, long ownerId, String channel, String clientMessageId) {
        String tenant = tenantProperties.isEnabled() ? TenantContext.require() : TenantContext.DEFAULT;
        return new WorkspaceMessageScope(tenant, ownerId, agentCode, WorkspaceRuntimeScope.safeSession(sessionId), channel, clientMessageId);
    }

    private Flux<ChatStreamChunk> replay(ChatReceipt receipt) {
        return Flux.just(ChatStreamChunk.accepted(receipt), ChatStreamChunk.terminal(
            receipt.terminal() == null ? ChatTerminal.unknown(null) : receipt.terminal()));
    }

    private void finishSafely(WorkspaceMessageScope scope, ChatTerminal terminal) {
        try {
            store.finish(scope, terminal);
        } catch (RuntimeException error) {
            log.error("workspace receipt finalization failed, errorCode={}, clientMessageId={}",
                ERROR_RECEIPT_SAVE, scope.clientMessageId(), error);
        }
    }

    /** 长度前缀消除字段拼接歧义；有序附件列表也是原请求的一部分，不散列 JSON 序列化顺序。 */
    private String fingerprint(ChatRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hashField(digest, request.message());
            hashField(digest, request.originalInput());
            hashField(digest, request.mode());
            hashField(digest, Boolean.toString(request.collaborationEnabled()));
            List<String> attachments = request.attachmentIds() == null ? List.of() : request.attachmentIds();
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(attachments.size()).array());
            attachments.forEach(value -> hashField(digest, value));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void hashField(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

}
