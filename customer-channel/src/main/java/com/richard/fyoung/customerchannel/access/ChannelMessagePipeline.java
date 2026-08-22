package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerchannel.access.model.ChannelInboundMessage;
import com.richard.fyoung.customerchannel.access.model.ChannelReplySender;
import com.richard.fyoung.customerchannel.access.support.KeyedSerialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 渠道消息处理管道（与具体渠道解耦）。
 *
 * <p>职责：指令解析（{@code /new}、{@code 新会话} → 重置会话）、普通文本 → resolve 会话 + 对话聚合、
 * 非文本 → 提示。同一会话（{@link ChannelInboundMessage#serialKey()}）串行，不同会话并行。</p>
 * @author owlzhangfq@gmail.com
 */
public class ChannelMessagePipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessagePipeline.class);

    private final AdminOpenApiClient adminClient;
    private final ChannelAccessProperties properties;
    private final KeyedSerialExecutor serialExecutor;

    public ChannelMessagePipeline(AdminOpenApiClient adminClient, ChannelAccessProperties properties) {
        this.adminClient = adminClient;
        this.properties = properties;
        this.serialExecutor = new KeyedSerialExecutor("channel-access-msg");
    }

    /**
     * 提交一条消息（按会话串行异步处理，立即返回，不阻塞连接器的收消息线程）。
     *
     * @param message 归一化入站消息
     * @param reply   平台特定回复发送器
     */
    public void submit(ChannelInboundMessage message, ChannelReplySender reply) {
        serialExecutor.submit(message.serialKey(), () -> handle(message, reply));
    }

    /**
     * 同步处理一条消息（供 submit 调度，也便于单测直接调用）。
     */
    void handle(ChannelInboundMessage message, ChannelReplySender reply) {
        try {
            if (!message.isText()) {
                reply.send(ChannelAccessConstants.HINT_NON_TEXT);
                return;
            }
            String text = message.getContent() == null ? "" : message.getContent().trim();
            boolean perMessage = isPerMessageMode(message);
            if (isNewSessionCommand(text)) {
                // 单次问答模式本就无上下文，重置指令仅提示，不打 admin
                if (perMessage) {
                    reply.send(ChannelAccessConstants.HINT_PER_MESSAGE_NO_RESET);
                    return;
                }
                adminClient.resetSession(message.getChannelType(), message.getAppKey(), message.getExternalUserId());
                reply.send(ChannelAccessConstants.HINT_NEW_SESSION);
                return;
            }
            // 单次问答：本地生成一次性 sessionId，跳过 admin 会话解析，每条消息全新上下文
            String sessionId = perMessage
                ? ChannelAccessConstants.ONESHOT_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "")
                : adminClient.resolveSession(
                    message.getChannelType(), message.getAppKey(), message.getExternalUserId());
            String answer = adminClient.chat(
                message.getAgentCode(), sessionId, text, message.getChannelType(), message.getAppKey(),
                properties.getChatTimeoutSeconds());
            reply.send(answer);
        } catch (Exception e) {
            log.error("channel message handle failed, code={}, channelType={}, externalUserId={}",
                ChannelAccessConstants.CODE_HANDLE_FAIL,
                message.getChannelType(), message.getExternalUserId(), e);
            replyErrorQuietly(reply);
        }
    }

    /** 会话模式判定：仅显式 per_message 走单次问答，空值/未知值一律按持续会话（兼容旧配置）。 */
    private boolean isPerMessageMode(ChannelInboundMessage message) {
        return ChannelAccessConstants.SESSION_MODE_PER_MESSAGE.equals(message.getSessionMode());
    }

    private boolean isNewSessionCommand(String text) {
        return ChannelAccessConstants.CMD_NEW_SLASH.equals(text)
            || ChannelAccessConstants.CMD_NEW_CN.equals(text);
    }

    private void replyErrorQuietly(ChannelReplySender reply) {
        try {
            reply.send(ChannelAccessConstants.HINT_ERROR);
        } catch (Exception e) {
            log.error("channel error reply failed, code={}", ChannelAccessConstants.CODE_REPLY_FAIL, e);
        }
    }

    @Override
    public void close() {
        serialExecutor.close();
    }
}
