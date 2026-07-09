package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 对话附件解析：把用户上传的 .md/.txt 文件读成纯文本，供 {@code ChatPanel}/{@code VibeCodingPanel}
 * 发送前拼进消息正文，让大模型能"看到"附件内容——跟 MCP/Skill 不一样，这里不接入 Toolkit/SkillBox，
 * 单纯是一次性文本读取，解析结果由前端插入输入框、随普通消息一起发送，不新增数据库结构、
 * 不改动 SSE 协议本身（附件本质上就是消息文本的一部分）。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChatAttachmentService {

    /** 附件只是拼进 prompt 的一段文本，不像 Skill 上传要塞进 zip；限制小一点，避免一次性喂进过大上下文。 */
    private static final long MAX_UPLOAD_BYTES = 2 * 1024 * 1024;

    public String parseAttachment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件大小超过 2MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".md") && !filename.endsWith(".txt")) {
            throw new BizException(ResultCode.PARAM_INVALID, "仅支持上传 .md 或 .txt 文件");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件读取失败: " + e.getMessage());
        }
    }
}
