package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentChangeRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRunVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeDocumentOperation;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.data.attachment.DocumentTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把运营上传的文档文件灌进知识库。
 *
 * <h3>为什么需要它</h3>
 * <p>知识库此前<b>只有 PUSH 一种入库方式</b>：调用方在 JSON 里塞纯文本。也就是说运营要把
 * 一份 PDF 或 Word 的制度文档放进知识库，得自己先转成纯文本、再调 API——而 Tika 就在
 * starter 的 classpath 里、对话附件那条链路早就在用它。能力有、入口没有。</p>
 *
 * <h3>三条设计约定</h3>
 * <ol>
 *   <li><b>永远不是全量快照</b>。{@code fullSnapshot=true} 的语义是「这批就是该源的全部内容，
 *       缺失的自动删除」——上传三个文件时若按全量提交，该源里其余文档会被<b>全部删掉</b>。
 *       这里硬编码为增量，不给调用方留下传错这个开关的机会。</li>
 *   <li><b>externalId 取文件名</b>：同名文件再传即更新同一篇文档，符合「传个新版覆盖旧版」的直觉。
 *       相应地同一批里出现重名必须当场拒绝——底层会因 externalId 重复报错，
 *       但那时错误信息里只有一个 id，运营看不出是自己选了两个同名文件。</li>
 *   <li><b>checkpoint 由服务端读当前值做 CAS 推进</b>，不让调用方传。代价是：同一个源
 *       如果既接外部推送又用手工上传，两边会互相把 checkpoint 顶掉——但那是一次<b>显式的
 *       CAS 失败</b>，比静默错乱好得多。手工上传应当用独立的文档源。</li>
 * </ol>
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class KnowledgeDocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentUploadService.class);

    private static final String UPLOAD_FAIL_CODE = "KB-DOC-UPLOAD-FAIL";

    /** 单次上传的文件数量上限，与底层单批次文档数上限保持同一量级。 */
    private static final int MAX_FILES_PER_UPLOAD = 20;

    /** 单个文件大小上限。超过这个体积的多半是资料包而不是一篇知识。 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSourceSyncService syncService;
    private final DocumentTextExtractor textExtractor;

    public KnowledgeDocumentUploadService(KnowledgeSourceService sourceService,
                                          KnowledgeSourceSyncService syncService,
                                          DocumentTextExtractor textExtractor) {
        this.sourceService = sourceService;
        this.syncService = syncService;
        this.textExtractor = textExtractor;
    }

    /**
     * 解析并入库一批文件。
     *
     * <p>任一文件解析失败即整批拒绝，不做「部分成功」：部分成功会让运营以为传完了，
     * 而少掉的那几篇要等到用户问起来才发现。失败信息里点名是哪个文件。</p>
     */
    public KnowledgeSyncRunVO upload(Long knowledgeBaseId, Long sourceId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "没有选择任何文件");
        }
        if (files.size() > MAX_FILES_PER_UPLOAD) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "单次最多上传 " + MAX_FILES_PER_UPLOAD + " 个文件，本次 " + files.size() + " 个");
        }
        AiKnowledgeSource source = sourceService.requireSource(knowledgeBaseId, sourceId);

        List<KnowledgeDocumentChangeRequest> documents = new ArrayList<>(files.size());
        Set<String> names = new HashSet<>();
        StringBuilder fingerprint = new StringBuilder();
        for (MultipartFile file : files) {
            String fileName = originalName(file);
            if (!names.add(fileName)) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "同一批里有重名文件：" + fileName + "。它们会被当成同一篇文档，请改名后重试");
            }
            documents.add(toChange(file, fileName, fingerprint));
        }

        String requestId = "upload-" + DigestUtils.md5DigestAsHex(
            fingerprint.toString().getBytes(StandardCharsets.UTF_8));
        KnowledgeSyncRequest request = new KnowledgeSyncRequest(
            requestId,
            source.getCurrentCheckpoint(),
            // checkpoint 必须推进且不得与 expected 相同；请求指纹天然满足这两点
            "upload-" + System.currentTimeMillis() + "-" + requestId.substring(requestId.length() - 8),
            // 刻意恒为 false：全量快照会删掉该源里这批之外的全部文档
            Boolean.FALSE,
            documents.size(),
            List.copyOf(documents));

        log.info("knowledge document upload accepted, kbId={}, sourceId={}, files={}",
            knowledgeBaseId, sourceId, documents.size());
        return syncService.sync(knowledgeBaseId, sourceId, request);
    }

    private KnowledgeDocumentChangeRequest toChange(MultipartFile file, String fileName,
                                                    StringBuilder fingerprint) {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "文件超过 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB 限制：" + fileName);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("read uploaded knowledge document failed, code={}, file={}",
                UPLOAD_FAIL_CODE, fileName, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "读取文件失败：" + fileName);
        }

        String content;
        try {
            content = textExtractor.extract(bytes, fileName);
        } catch (IllegalArgumentException e) {
            // 类型不支持、扫描件无文本层这类问题要原样告诉运营，他们才知道下一步怎么办
            throw new BizException(ResultCode.PARAM_INVALID, e.getMessage());
        } catch (Exception e) {
            log.error("extract uploaded knowledge document failed, code={}, file={}",
                UPLOAD_FAIL_CODE, fileName, e);
            throw new BizException(ResultCode.PARAM_INVALID, "文件解析失败：" + fileName);
        }

        // 内容进指纹：同一批文件原样重传是同一个 requestId，底层据此幂等，不会重复建一次同步批次
        fingerprint.append(fileName).append(':')
            .append(DigestUtils.md5DigestAsHex(bytes)).append(';');

        return new KnowledgeDocumentChangeRequest(
            KnowledgeDocumentOperation.UPSERT.name(),
            fileName,
            DigestUtils.md5DigestAsHex(bytes),
            fileName,
            // 手工上传没有可回溯的外部地址，留空而不是编一个
            null,
            content,
            LocalDateTime.now(),
            null);
    }

    private String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (!StringUtils.hasText(name)) {
            throw new BizException(ResultCode.PARAM_INVALID, "上传的文件缺少文件名");
        }
        // 浏览器在某些场景会带上路径，只取最后一段，避免 externalId 里混进目录结构
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String bare = slash >= 0 ? name.substring(slash + 1) : name;
        if (!StringUtils.hasText(bare)) {
            throw new BizException(ResultCode.PARAM_INVALID, "上传的文件名不合法：" + name);
        }
        return bare;
    }
}
