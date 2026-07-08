package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VibeCoding：明确不自研代码生成引擎——就是 chat 能力 + Harness Sandbox（文件读写/代码执行）+
 * 定向系统提示词（实施计划 3.4 节）。本类只负责两件事：① 校验智能体确有 {@code vibecoding} 能力后
 * 复用 {@link ChatService} 走同一套流式对话；② 降级版产物清单——对话前后对比 workspace 目录的文件
 * 快照，一次性返回变更文件清单，不做实时 {@code file_change} 事件（工程量大，一期明确砍掉）。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VibeCodingService {

    private static final Logger log = LoggerFactory.getLogger(VibeCodingService.class);
    private static final String CAPABILITY_VIBECODING = "vibecoding";
    private static final String CAPABILITY_DELIMITER = ",";

    private final ChatService chatService;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final AiAgentMapper agentMapper;

    /** {@code agentCode:sessionId -> 对话开始前的目录快照}，进程内、重启丢失（v1 降级方案的一部分）。 */
    private final Map<String, Map<String, FileFingerprint>> beforeSnapshots = new ConcurrentHashMap<>();

    public VibeCodingService(ChatService chatService, AdminAgentInstanceFactory agentInstanceFactory,
                              AiAgentMapper agentMapper) {
        this.chatService = chatService;
        this.agentInstanceFactory = agentInstanceFactory;
        this.agentMapper = agentMapper;
    }

    /** 对话开始前先拍一次 workspace 快照，再复用 ChatService 的流式对话。 */
    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText) {
        requireVibeCodingCapable(agentCode);
        beforeSnapshots.put(snapshotKey(agentCode, sessionId), snapshot(agentInstanceFactory.resolveWorkspace(agentCode)));
        return chatService.chatStream(agentCode, sessionId, userText);
    }

    /** 拿当前 workspace 目录快照与对话开始前的快照比对，返回新增/修改的文件相对路径清单。 */
    public List<String> listChangedArtifacts(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Map<String, FileFingerprint> before = beforeSnapshots.getOrDefault(snapshotKey(agentCode, sessionId), Map.of());
        Map<String, FileFingerprint> after = snapshot(agentInstanceFactory.resolveWorkspace(agentCode));

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, FileFingerprint> entry : after.entrySet()) {
            FileFingerprint beforeFingerprint = before.get(entry.getKey());
            if (!entry.getValue().equals(beforeFingerprint)) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    private void requireVibeCodingCapable(String agentCode) {
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, agentCode));
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + agentCode);
        }
        List<String> capabilities = StringUtils.hasText(agent.getCapabilities())
            ? Arrays.asList(agent.getCapabilities().split(CAPABILITY_DELIMITER)) : List.of();
        if (!capabilities.contains(CAPABILITY_VIBECODING)) {
            throw new BizException(ResultCode.AGENT_CAPABILITY_NOT_SUPPORTED, "智能体未开启 vibecoding 能力: " + agentCode);
        }
    }

    private String snapshotKey(String agentCode, String sessionId) {
        return agentCode + ":" + (StringUtils.hasText(sessionId) ? sessionId : "default");
    }

    /** 递归遍历 workspace 目录，记录每个文件的相对路径 -> (大小, 最后修改时间) 指纹。 */
    private Map<String, FileFingerprint> snapshot(Path workspace) {
        Map<String, FileFingerprint> fingerprints = new HashMap<>();
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relativePath = workspace.relativize(file).toString();
                    fingerprints.put(relativePath, new FileFingerprint(attrs.size(), attrs.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("[workspace] vibecoding snapshot failed, code={}, workspace={}", "VIBECODING_SNAPSHOT_ERROR", workspace, e);
        }
        return fingerprints;
    }

    private record FileFingerprint(long size, long lastModifiedMillis) {
    }
}
