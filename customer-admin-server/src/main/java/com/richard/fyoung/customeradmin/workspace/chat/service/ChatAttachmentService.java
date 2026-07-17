package com.richard.fyoung.customeradmin.workspace.chat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatAttachmentDTO;
import com.richard.fyoung.customeradmin.workspace.chat.store.AdminChatAttachmentStore;
import com.richard.fyoung.customerwork.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.attachment.ChatAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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

    private final AttachmentParseService attachmentParseService;
    private final AdminChatAttachmentStore attachmentStore;

    public ChatAttachmentService(AttachmentParseService attachmentParseService,
                                 AdminChatAttachmentStore attachmentStore) {
        this.attachmentParseService = attachmentParseService;
        this.attachmentStore = attachmentStore;
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
