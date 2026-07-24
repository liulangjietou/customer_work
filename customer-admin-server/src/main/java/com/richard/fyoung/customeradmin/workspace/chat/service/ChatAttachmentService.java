package com.richard.fyoung.customeradmin.workspace.chat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatAttachmentDTO;
import com.richard.fyoung.customeradmin.workspace.chat.store.AdminChatAttachmentStore;
import com.richard.fyoung.customerwork.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.attachment.ChatAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 对话附件解析：委托 starter 的 {@link AttachmentParseService} 完成多格式解析（图片视觉大模型 OCR、
 * pdf/office/html 走 Tika/POI、md/txt/csv/json 直读），统一落盘 + 落库，返回前端契约 {@link ChatAttachmentDTO}。
 *
 * <p>本类只做"取上下文 + 委托 + 结果转换 + 异常翻译"：白名单/大小的<b>防御式校验只在
 * {@code AttachmentParseService} 一处</b>，本类不重复校验，仅把编排层抛出的 {@link IllegalArgumentException}
 * （不支持的类型 / 超大小上限）翻译成 admin 的 {@link BizException}（走 {@code GlobalExceptionHandler} 出标准错误体）。
 * 解析失败（如损坏的文档）不抛异常，编排层落 FAILED 记录并正常返回，前端据 {@code parseStatus} 提示并跳过拼接。</p>
 *
 * <p>agent_code 通过 {@link AdminChatAttachmentStore#bindAgentCode} 绑定到当前请求线程后由 store 落库
 * （原因见该类注释：落库发生在编排层内部，领域对象不承载 agent_code）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    /** MIME 兜底：库中 mime 为空时按二进制流下发，交由前端/浏览器不做类型嗅探（配合 nosniff）。 */
    private static final String DEFAULT_MIME = "application/octet-stream";

    private final AttachmentParseService attachmentParseService;
    private final AdminChatAttachmentStore attachmentStore;
    private final AttachmentFileStorage attachmentFileStorage;

    public ChatAttachmentService(AttachmentParseService attachmentParseService,
                                 AdminChatAttachmentStore attachmentStore,
                                 AttachmentFileStorage attachmentFileStorage) {
        this.attachmentParseService = attachmentParseService;
        this.attachmentStore = attachmentStore;
        this.attachmentFileStorage = attachmentFileStorage;
    }

    /**
     * 解析并存储一个附件。
     *
     * @param file      上传文件
     * @param channel   来源渠道：admin_chat（ChatPanel）/ vibecoding（VibeCodingPanel）
     * @param sessionId 会话 ID（可空，前端当前不下传，保留以便后续按会话追溯）
     * @param agentCode 智能体编码（Controller 路径参数，落 ai_chat_attachment.agent_code）
     * @return 前端契约 DTO（含解析文本或失败原因）
     */
    public ChatAttachmentDTO parseAttachment(MultipartFile file, String channel, String sessionId, String agentCode) {
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            log.error("attachment read bytes failed, code={}, file={}",
                "ADMIN-ATTACHMENT-READ-FAIL", file.getOriginalFilename(), e);
            throw new BizException(ResultCode.PARAM_INVALID, "文件读取失败");
        }

        String uploader = currentUploader();
        attachmentStore.bindAgentCode(agentCode);
        try {
            ChatAttachment attachment = attachmentParseService.parseAndStore(
                data, file.getOriginalFilename(), sessionId, uploader, channel);
            return ChatAttachmentDTO.from(attachment);
        } catch (IllegalArgumentException e) {
            // 编排层唯一防御点抛出的入参校验失败（类型不支持 / 超大小上限），翻译成标准业务错误
            throw new BizException(ResultCode.PARAM_INVALID, e.getMessage());
        } finally {
            attachmentStore.clearAgentCode();
        }
    }

    /**
     * 把一批附件绑定到某条用户消息（随消息发送时回填 session_id + message_id）。
     *
     * <p>旁路动作，绝不打断对话主流程：空列表短路；数量不符只 info（部分 id 不存在 / 不属该 agent 属正常，
     * 前端可能带上历史/他人附件）；持久化异常 {@code catch(Exception)} 只 error 记录（带错误码）。</p>
     *
     * @param agentCode     智能体编码（agent 归属兜底，只绑该智能体名下的附件）
     * @param sessionId     会话 ID（归一后的值，与历史查询口径一致）
     * @param messageId     绑定的用户消息 ID（框架 Msg.id）
     * @param attachmentIds 本条消息携带的附件 ID 列表
     */
    public void bindToMessage(String agentCode, String sessionId, String messageId, List<String> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return;
        }
        try {
            int updated = attachmentStore.bindToMessage(agentCode, sessionId, messageId, attachmentIds);
            if (updated != attachmentIds.size()) {
                log.info("chat attachment bind count mismatch, agentCode={}, messageId={}, expected={}, updated={}",
                    agentCode, messageId, attachmentIds.size(), updated);
            }
        } catch (Exception e) {
            log.error("chat attachment bind failed, code={}, agentCode={}, messageId={}",
                "ADMIN-ATTACHMENT-BIND-FAIL", agentCode, messageId, e);
        }
    }

    /**
     * 附件详情：校验存在性 + agent 归属后返回前端契约 DTO（{@code content}=解析文本，供文本类附件内联预览）。
     * 附件不存在或跨 agent 访问统一 fast-fail 成 {@link ResultCode#RESOURCE_NOT_FOUND}（不泄露"是否存在"）。
     */
    public ChatAttachmentDTO getDetail(String agentCode, String attachmentId) {
        return ChatAttachmentDTO.from(requireOwned(agentCode, attachmentId));
    }

    /**
     * 读原文件字节：校验存在性 + agent 归属 → 经 {@link AttachmentFileStorage#read} 读回字节，
     * 返回含 bytes/mimeType/fileName 的小结果对象供 Controller 组装下载响应。mime 为空按二进制流兜底。
     * 读盘/读对象失败翻译成友好业务异常（不把 IO 细节暴露给前端）。
     */
    public LoadedFile loadFile(String agentCode, String attachmentId) {
        ChatAttachment attachment = requireOwned(agentCode, attachmentId);
        try {
            byte[] bytes = attachmentFileStorage.read(attachment.getStoragePath());
            String mime = StringUtils.hasText(attachment.getMimeType()) ? attachment.getMimeType() : DEFAULT_MIME;
            return new LoadedFile(bytes, mime, attachment.getFileName());
        } catch (IOException e) {
            log.error("chat attachment read file failed, code={}, id={}", "ADMIN-ATTACHMENT-READ-FILE-FAIL", attachmentId, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "附件文件读取失败，请稍后重试");
        }
    }

    /** 存在性 + agent 归属校验合一：查不到（不存在 / 跨 agent）即 fast-fail 成 NOT_FOUND。 */
    private ChatAttachment requireOwned(String agentCode, String attachmentId) {
        return attachmentStore.findByIdAndAgentCode(attachmentId, agentCode)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "附件不存在"));
    }

    /** 原文件读取结果：字节 + MIME + 原始文件名（Controller 据此组装 {@code ResponseEntity<byte[]>}）。 */
    public record LoadedFile(byte[] bytes, String mimeType, String fileName) {
    }

    /** 取当前登录管理员 ID 作上传者标识；无 Sa-Token 上下文（如单测）时留空，不阻断上传。 */
    private String currentUploader() {
        try {
            if (StpUtil.isLogin()) {
                return String.valueOf(StpUtil.getLoginId());
            }
        } catch (Exception e) {
            log.error("resolve attachment uploader failed, code={}", "ADMIN-ATTACHMENT-UPLOADER-FAIL", e);
        }
        return "";
    }
}
