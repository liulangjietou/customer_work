package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillFileVO;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillExportPackage;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadParseResult;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillVO;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkillFile;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillFileMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import com.richard.fyoung.customerwork.data.skill.storage.SkillContentPublisher;
import com.richard.fyoung.customerwork.data.skill.storage.SkillFileContent;
import com.richard.fyoung.customerwork.data.skill.storage.SkillStorageTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Skill 管理。
 *
 * <p>zip 上传的技能包 = SKILL.md 本体 + references/scripts 等附属文件：SKILL.md 存
 * {@code ai_skill.content}，附属文件全量存 {@code ai_skill_file}（文本/二进制统一按字节），
 * 保存时随事务落库并发布到勾选的存储目标；运行时由 AdminAgentInstanceFactory 把两者一起
 * 落盘交给 FileSystemSkillRepository 加载，技能里引用的脚本/文档才真正生效。</p>
 *
 * <p>新建/编辑时除入库外，把 SKILL.md 正文与附属文件发布到用户勾选的存储目标（local/nacos/sftp）。
 * 发布走 {@link SkillContentPublisher} SPI，发布失败让事务回滚，保证"保存成功=目标已上传"；
 * 取消勾选/删除时对相应目标做尽力而为的清理。智能体运行时消费仍从数据库读，不经这些目标。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** 存储目标发布相关错误码（日志占位符 / 业务异常消息用）。 */
    private static final String ERR_TARGET_INVALID = "SKILL-STORAGE-TARGET-INVALID";
    private static final String ERR_TARGET_DISABLED = "SKILL-STORAGE-TARGET-DISABLED";
    private static final String ERR_PUBLISH_FAIL = "SKILL-STORAGE-PUBLISH-FAIL";
    private static final String ERR_EXPORT_FAIL = "SKILL-EXPORT-FAIL";

    private static final String TARGET_DELIMITER = ",";

    private final AiSkillMapper skillMapper;
    private final AiSkillFileMapper skillFileMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentMapper agentMapper;
    private final AgentInstanceCache agentInstanceCache;
    /** 按目标索引的发布器；未启用的目标（nacos/sftp）在容器中无对应 Bean，故此处无键。 */
    private final Map<SkillStorageTarget, SkillContentPublisher> publishers;

    public SkillService(AiSkillMapper skillMapper, AiSkillFileMapper skillFileMapper,
                         AiAgentSkillMapper agentSkillMapper, AiAgentMapper agentMapper,
                         AgentInstanceCache agentInstanceCache, List<SkillContentPublisher> contentPublishers) {
        this.skillMapper = skillMapper;
        this.skillFileMapper = skillFileMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceCache = agentInstanceCache;
        this.publishers = new EnumMap<>(SkillStorageTarget.class);
        for (SkillContentPublisher publisher : contentPublishers) {
            this.publishers.put(publisher.target(), publisher);
        }
    }

    public PageResult<SkillVO> page(PageQuery query) {
        LambdaQueryWrapper<AiSkill> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiSkill::getSkillName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiSkill::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiSkill::getCreateTime);

        IPage<AiSkill> page = skillMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        // 附属文件清单一次批查（只取路径/大小，不捞 LONGBLOB），避免逐行 N+1
        Map<Long, List<SkillFileVO>> filesBySkill = fileMetasBySkillIds(
            page.getRecords().stream().map(AiSkill::getId).collect(Collectors.toList()));
        return PageResult.of(page.convert(skill ->
            toVo(skill, filesBySkill.getOrDefault(skill.getId(), List.of()))));
    }

    public SkillVO get(Long id) {
        AiSkill skill = requireSkill(id);
        return toVo(skill, fileMetasBySkillIds(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(SkillSaveRequest request) {
        if (skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        List<SkillStorageTarget> targets = resolveTargets(request.storageTargets());
        AiSkill skill = new AiSkill();
        fillFromRequest(skill, request, targets);
        skillMapper.insert(skill);
        replaceFiles(skill.getId(), request.files() == null ? List.of() : request.files());
        // 入库成功后发布，发布失败抛业务异常回滚事务，保证"保存成功=目标已上传"
        publishToTargets(skill.getSkillCode(), skill.getContent(), loadFileContents(skill.getId()), targets);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SkillSaveRequest request) {
        AiSkill skill = requireSkill(id);
        String oldSkillCode = skill.getSkillCode();
        List<SkillStorageTarget> oldTargets = parseStoredTargets(skill.getStorageTargets());
        if (!skill.getSkillCode().equals(request.skillCode())
            && skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        List<SkillStorageTarget> targets = resolveTargets(request.storageTargets());
        fillFromRequest(skill, request, targets);
        skillMapper.updateById(skill);
        // files == null 表示本次未重新上传，保持现有附属文件；非 null（含空列表）全量替换
        if (request.files() != null) {
            replaceFiles(id, request.files());
        }
        // 覆盖发布到本次勾选的目标（失败回滚）；附属文件从库里读当前全量（未重传时即原有文件）
        publishToTargets(skill.getSkillCode(), skill.getContent(), loadFileContents(id), targets);
        evictAgentsReferencingSkill(id);
        // 本次取消勾选的目标：尽力清理旧产物，失败仅记日志不阻断
        List<SkillStorageTarget> cancelled = new ArrayList<>(oldTargets);
        cancelled.removeAll(targets);
        removeFromTargetsQuietly(oldSkillCode, cancelled);
    }

    /** Skill 内容变更（content/附属文件等）会让引用它的智能体运行时用上旧技能包，需一并失效。 */
    private void evictAgentsReferencingSkill(Long skillId) {
        List<Long> agentIds = agentSkillMapper.selectList(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getSkillId, skillId))
            .stream().map(AiAgentSkill::getAgentId).collect(Collectors.toList());
        if (agentIds.isEmpty()) {
            return;
        }
        List<String> agentCodes = agentMapper.selectBatchIds(agentIds).stream()
            .map(AiAgent::getAgentCode).collect(Collectors.toList());
        agentInstanceCache.evictAll(agentCodes);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiSkill skill = requireSkill(id);
        if (agentSkillMapper.exists(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getSkillId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该 Skill 正被智能体引用，无法删除");
        }
        skillMapper.deleteById(id);
        skillFileMapper.delete(new LambdaQueryWrapper<AiSkillFile>().eq(AiSkillFile::getSkillId, id));
        // 落库的所有目标逐个尽力清理，失败仅记日志不阻断删除
        removeFromTargetsQuietly(skill.getSkillCode(), parseStoredTargets(skill.getStorageTargets()));
    }

    // ---- 附属文件落库/读取 ----

    /** 全量替换附属文件：路径合法性/总大小在此统一校验（保存链路唯一防御点），base64 解码后按字节落库。 */
    private void replaceFiles(Long skillId, List<SkillUploadFile> files) {
        skillFileMapper.delete(new LambdaQueryWrapper<AiSkillFile>().eq(AiSkillFile::getSkillId, skillId));
        long totalBytes = 0;
        Set<String> seenPaths = new LinkedHashSet<>();
        for (SkillUploadFile file : files) {
            String path = requireSafeRelativePath(file.filePath());
            if (!seenPaths.add(path)) {
                throw new BizException(ResultCode.PARAM_INVALID, "附属文件路径重复: " + path);
            }
            byte[] bytes = decodeBase64(path, file.contentBase64());
            totalBytes += bytes.length;
            if (totalBytes > MAX_UNZIPPED_BYTES) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "附属文件总大小超过 " + (MAX_UNZIPPED_BYTES / BYTES_PER_MB) + "MB 限制");
            }
            AiSkillFile row = new AiSkillFile();
            row.setSkillId(skillId);
            row.setFilePath(path);
            row.setFileSize((long) bytes.length);
            row.setContent(bytes);
            skillFileMapper.insert(row);
        }
    }

    /** 读取某 skill 的全部附属文件（含内容），供发布到存储目标。 */
    private List<SkillFileContent> loadFileContents(Long skillId) {
        return skillFileMapper.selectList(new LambdaQueryWrapper<AiSkillFile>().eq(AiSkillFile::getSkillId, skillId))
            .stream().map(f -> new SkillFileContent(f.getFilePath(), f.getContent()))
            .collect(Collectors.toList());
    }

    /** 批查附属文件清单（只取 skill_id/file_path/file_size，不捞 LONGBLOB），按 skillId 分组。 */
    private Map<Long, List<SkillFileVO>> fileMetasBySkillIds(List<Long> skillIds) {
        if (CollectionUtils.isEmpty(skillIds)) {
            return Map.of();
        }
        return skillFileMapper.selectList(new LambdaQueryWrapper<AiSkillFile>()
                .select(AiSkillFile::getSkillId, AiSkillFile::getFilePath, AiSkillFile::getFileSize)
                .in(AiSkillFile::getSkillId, skillIds))
            .stream().collect(Collectors.groupingBy(AiSkillFile::getSkillId,
                Collectors.mapping(f -> new SkillFileVO(f.getFilePath(), f.getFileSize()), Collectors.toList())));
    }

    private byte[] decodeBase64(String path, String contentBase64) {
        if (contentBase64 == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "附属文件缺少内容: " + path);
        }
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "附属文件内容不是合法 base64: " + path);
        }
    }

    // ---- 存储目标发布/清理 ----

    /** 校验并规整请求的存储目标；空/null 默认 {@code [MINIO]}，非法值 fast fail。 */
    private List<SkillStorageTarget> resolveTargets(List<String> raw) {
        if (CollectionUtils.isEmpty(raw)) {
            return List.of(SkillStorageTarget.MINIO);
        }
        Set<SkillStorageTarget> targets = new LinkedHashSet<>();
        for (String code : raw) {
            SkillStorageTarget target = SkillStorageTarget.fromCode(code)
                .orElseThrow(() -> new BizException(ResultCode.PARAM_INVALID,
                    "[" + ERR_TARGET_INVALID + "] storageTargets 仅支持 local/nacos/sftp，非法值: " + code));
            targets.add(target);
        }
        return new ArrayList<>(targets);
    }

    /** 发布到指定目标（SKILL.md + 附属文件）；目标未启用或发布失败均抛业务异常（回滚事务）。 */
    private void publishToTargets(String skillCode, String content, List<SkillFileContent> files,
                                  List<SkillStorageTarget> targets) {
        for (SkillStorageTarget target : targets) {
            SkillContentPublisher publisher = publishers.get(target);
            if (publisher == null) {
                log.error("skill storage publish blocked, code={}, target={}, skillCode={}",
                    ERR_TARGET_DISABLED, target.getCode(), skillCode);
                throw new BizException(ResultCode.PARAM_INVALID,
                    "[" + ERR_TARGET_DISABLED + "] 存储目标未启用: " + target.getCode());
            }
            try {
                publisher.publish(skillCode, content);
                publisher.publishFiles(skillCode, files);
            } catch (Exception e) {
                log.error("skill storage publish failed, code={}, target={}, skillCode={}",
                    ERR_PUBLISH_FAIL, target.getCode(), skillCode, e);
                throw new BizException(ResultCode.SYSTEM_ERROR,
                    "[" + ERR_PUBLISH_FAIL + "] 发布到存储目标失败: " + target.getCode());
            }
        }
    }

    /** 尽力而为地移除目标上的旧产物：目标未启用则跳过，异常仅记日志不上抛。 */
    private void removeFromTargetsQuietly(String skillCode, List<SkillStorageTarget> targets) {
        for (SkillStorageTarget target : targets) {
            SkillContentPublisher publisher = publishers.get(target);
            if (publisher == null) {
                continue;
            }
            try {
                publisher.remove(skillCode);
            } catch (Exception e) {
                log.error("skill storage remove failed, code={}, target={}, skillCode={}",
                    ERR_PUBLISH_FAIL, target.getCode(), skillCode, e);
            }
        }
    }

    /** 解析落库的逗号分隔目标串，非法/未知片段跳过（历史数据容错）。 */
    private List<SkillStorageTarget> parseStoredTargets(String stored) {
        if (!StringUtils.hasText(stored)) {
            return List.of();
        }
        List<SkillStorageTarget> targets = new ArrayList<>();
        for (String code : stored.split(TARGET_DELIMITER)) {
            SkillStorageTarget.fromCode(code).ifPresent(targets::add);
        }
        return targets;
    }

    // ---- 导出 ----

    /**
     * 导出为技能包 zip。
     *
     * <p><b>结构与上传解析严格对称</b>：zip 内包一层以 skillCode 命名的目录，目录里放
     * {@code SKILL.md}（取 {@code ai_skill.content}）与全部附属文件（按各自的相对路径还原）。
     * 这样下载下来的包能<b>原样重新上传</b>——{@link #parseSkillZip} 正是以最浅层 SKILL.md
     * 所在目录为技能根。改这里的结构前先想清楚往返还成不成立。</p>
     *
     * <p>包一层目录而不是把文件铺在 zip 根：解压时不会把一堆文件散进当前目录，
     * 也与技能包本身"一个目录就是一个技能"的形态一致。</p>
     *
     * <p>内容全部来自库（{@code ai_skill} + {@code ai_skill_file}），<b>不读存储目标</b>：
     * {@code storage_targets} 是"发布到哪里去"，库才是权威副本。从 MinIO 回读既慢又可能
     * 读到被外部改过的版本，导出的就不是后台这一份了。</p>
     */
    public SkillExportPackage exportZip(Long id) {
        AiSkill skill = requireSkill(id);
        String rootDir = sanitizeFileName(skill.getSkillCode());
        List<SkillFileContent> files = loadFileContents(id);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            String content = skill.getContent() == null ? "" : skill.getContent();
            writeZipEntry(zipOut, rootDir + "/" + AgentFileNames.SKILL_MD, content.getBytes(StandardCharsets.UTF_8));
            for (SkillFileContent file : files) {
                // content 允许为空（历史数据/空文件），补空字节而不是跳过——
                // 跳过会让重新上传后文件清单凭空少几项，导出就不是"这个 skill 的全部"了
                byte[] bytes = file.content() == null ? new byte[0] : file.content();
                writeZipEntry(zipOut, rootDir + "/" + file.filePath(), bytes);
            }
        } catch (IOException e) {
            log.error("skill export failed, code={}, skillId={}", ERR_EXPORT_FAIL, id, e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "技能包打包失败: " + e.getMessage());
        }
        log.info("skill exported: skillCode={}, files={}", skill.getSkillCode(), files.size());
        return new SkillExportPackage(rootDir + ".zip", bos.toByteArray());
    }

    private void writeZipEntry(ZipOutputStream zipOut, String entryName, byte[] bytes) throws IOException {
        zipOut.putNextEntry(new ZipEntry(entryName));
        zipOut.write(bytes);
        zipOut.closeEntry();
    }

    /**
     * 把 skillCode 净化成合法文件名。
     *
     * <p>skillCode 是用户填的（现网就有"Apollo查值"这种），直接拿来当 zip 内的目录名与下载文件名，
     * 遇到 {@code /} 或 {@code ..} 会写出目录穿越的条目——那是压缩包里的经典问题，
     * 解压方按路径还原就落到目标目录之外去了。中文本身没问题（zip 与 Content-Disposition 都按 UTF-8 处理），
     * 只净化路径分隔符与各系统的文件名保留字符。</p>
     */
    private String sanitizeFileName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "skill";
        }
        String cleaned = raw.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        // 全是分隔符时上面会净化成一串下划线，仍给个兜底名，避免出现 ".zip" 这种无名文件
        return StringUtils.hasText(cleaned.replace("_", "")) ? cleaned : "skill";
    }

    // ---- 上传解析 ----

    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final long MAX_UPLOAD_BYTES = 5 * BYTES_PER_MB;
    /** zip 解压后（以及保存请求里附属文件解码后）的总字节上限，防 zip bomb。 */
    private static final long MAX_UNZIPPED_BYTES = 20 * BYTES_PER_MB;
    /** zip 条目数上限，防恶意海量小文件。 */
    private static final int MAX_ZIP_ENTRIES = 500;
    /** 解析时跳过的打包工具垃圾条目：macOS 的 __MACOSX/ 与 .DS_Store。 */
    private static final String MACOS_JUNK_DIR = "__macosx/";
    private static final String MACOS_JUNK_FILE = ".ds_store";

    /**
     * 解析上传文件为技能包：{@code .md} 直接整篇当 SKILL.md 正文（无附属文件）；{@code .zip} 以
     * 最浅层 SKILL.md（大小写不敏感）所在目录为技能根，正文取该 SKILL.md，根目录下其余全部文件
     * （含子目录、二进制）按相对路径收进附属文件列表。不落库——解析结果回填前端表单，
     * 仍走既有的 create/update 接口随事务保存。
     */
    public SkillUploadParseResult parseUploadContent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件大小超过 " + (MAX_UPLOAD_BYTES / BYTES_PER_MB) + "MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (filename.endsWith(".md")) {
                return new SkillUploadParseResult(new String(file.getBytes(), StandardCharsets.UTF_8), List.of());
            }
            if (filename.endsWith(".zip")) {
                return parseSkillZip(file);
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件读取失败: " + e.getMessage());
        }
        throw new BizException(ResultCode.PARAM_INVALID, "仅支持上传 .md 或 .zip 文件");
    }

    private SkillUploadParseResult parseSkillZip(MultipartFile file) throws IOException {
        Map<String, byte[]> entries = readZipEntries(file);
        String skillMdName = findShallowestSkillMd(entries.keySet());
        String rootPrefix = skillMdName.substring(0, skillMdName.lastIndexOf('/') + 1);
        String content = new String(entries.get(skillMdName), StandardCharsets.UTF_8);

        List<SkillUploadFile> files = new ArrayList<>();
        int skippedOutsideRoot = 0;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().equals(skillMdName)) {
                continue;
            }
            if (!entry.getKey().startsWith(rootPrefix)) {
                skippedOutsideRoot++;
                continue;
            }
            String relativePath = requireSafeRelativePath(entry.getKey().substring(rootPrefix.length()));
            files.add(new SkillUploadFile(relativePath, (long) entry.getValue().length,
                Base64.getEncoder().encodeToString(entry.getValue())));
        }
        if (skippedOutsideRoot > 0) {
            log.info("skill zip entries outside skill root skipped, skillMd={}, skipped={}",
                skillMdName, skippedOutsideRoot);
        }
        return new SkillUploadParseResult(content, files);
    }

    /** 顺序读出 zip 全部文件条目（路径→字节），跳过目录与打包垃圾，超条目数/解压总量上限 fast fail。 */
    private Map<String, byte[]> readZipEntries(MultipartFile file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long totalBytes = 0;
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(file.getBytes()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory() || isJunkEntry(entry.getName())) {
                    continue;
                }
                if (entries.size() >= MAX_ZIP_ENTRIES) {
                    throw new BizException(ResultCode.PARAM_INVALID, "zip 条目数超过 " + MAX_ZIP_ENTRIES + " 上限");
                }
                byte[] bytes = readEntryCapped(zipIn, totalBytes);
                totalBytes += bytes.length;
                entries.put(entry.getName(), bytes);
            }
        }
        return entries;
    }

    /** 按声明大小不可信的前提读条目，累计超过解压总量上限即 fast fail（防 zip bomb）。 */
    private byte[] readEntryCapped(ZipInputStream zipIn, long alreadyRead) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = zipIn.read(buffer)) > 0) {
            if (alreadyRead + bos.size() + len > MAX_UNZIPPED_BYTES) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "zip 解压后总大小超过 " + (MAX_UNZIPPED_BYTES / BYTES_PER_MB) + "MB 限制");
            }
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }

    private boolean isJunkEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith(MACOS_JUNK_DIR) || lower.endsWith(MACOS_JUNK_FILE);
    }

    /** 找最浅层（路径分隔符最少）的 SKILL.md 条目名；找不到报业务错误。 */
    private String findShallowestSkillMd(Set<String> entryNames) {
        String found = null;
        int foundDepth = Integer.MAX_VALUE;
        for (String name : entryNames) {
            String fileName = name.substring(name.lastIndexOf('/') + 1);
            if (!AgentFileNames.SKILL_MD.equalsIgnoreCase(fileName)) {
                continue;
            }
            int depth = (int) name.chars().filter(c -> c == '/').count();
            if (depth < foundDepth) {
                found = name;
                foundDepth = depth;
            }
        }
        if (found == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "zip 压缩包中未找到 SKILL.md");
        }
        return found;
    }

    /**
     * 附属文件相对路径防御（zip-slip/路径穿越）：拒绝空路径、绝对路径、反斜杠、{@code ..} 上跳与空段；
     * 解析与保存两个入口共用此唯一防御点，运行时落盘与发布器不再重复校验。
     */
    private String requireSafeRelativePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new BizException(ResultCode.PARAM_INVALID, "附属文件路径不能为空");
        }
        if (path.startsWith("/") || path.contains("\\")) {
            throw new BizException(ResultCode.PARAM_INVALID, "附属文件路径非法: " + path);
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || "..".equals(segment) || ".".equals(segment)) {
                throw new BizException(ResultCode.PARAM_INVALID, "附属文件路径非法: " + path);
            }
        }
        return path;
    }

    private void fillFromRequest(AiSkill skill, SkillSaveRequest request, List<SkillStorageTarget> targets) {
        skill.setSkillName(request.skillName());
        skill.setSkillCode(request.skillCode());
        skill.setContent(request.content());
        skill.setDescription(request.description());
        skill.setStatus(request.status() == null ? 1 : request.status());
        skill.setStorageTargets(targets.stream().map(SkillStorageTarget::getCode)
            .collect(Collectors.joining(TARGET_DELIMITER)));
    }

    private SkillVO toVo(AiSkill skill, List<SkillFileVO> files) {
        SkillVO vo = new SkillVO();
        vo.setId(skill.getId());
        vo.setSkillName(skill.getSkillName());
        vo.setSkillCode(skill.getSkillCode());
        vo.setContent(skill.getContent());
        vo.setDescription(skill.getDescription());
        vo.setStatus(skill.getStatus());
        vo.setStorageTargets(parseStoredTargets(skill.getStorageTargets()).stream()
            .map(SkillStorageTarget::getCode).collect(Collectors.toList()));
        vo.setFiles(files);
        vo.setCreateTime(skill.getCreateTime());
        return vo;
    }

    private AiSkill requireSkill(Long id) {
        AiSkill skill = skillMapper.selectById(id);
        if (skill == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "Skill 不存在: " + id);
        }
        return skill;
    }
}
