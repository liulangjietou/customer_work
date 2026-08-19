package com.richard.fyoung.customeradmin.aiconfig.skill.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillExportPackage;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadParseResult;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillVO;
import com.richard.fyoung.customeradmin.aiconfig.skill.service.SkillService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Skill 管理：CRUD + 分页/搜索/筛选/排序。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/skill")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @SaCheckPermission("skill:view")
    @GetMapping
    public Result<PageResult<SkillVO>> page(PageQuery query) {
        return Result.success(skillService.page(query));
    }

    @SaCheckPermission("skill:view")
    @GetMapping("/{id}")
    public Result<SkillVO> get(@PathVariable Long id) {
        return Result.success(skillService.get(id));
    }

    @SaCheckPermission("skill:add")
    @OperationLog(operation = "新建Skill", target = "ai_skill")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody SkillSaveRequest request) {
        skillService.create(request);
        return Result.success();
    }

    @SaCheckPermission("skill:edit")
    @OperationLog(operation = "编辑Skill", target = "ai_skill")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SkillSaveRequest request) {
        skillService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("skill:delete")
    @OperationLog(operation = "删除Skill", target = "ai_skill")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        skillService.delete(id);
        return Result.success();
    }

    /** 解析上传的 .md/.zip 文件为技能包（SKILL.md 正文 + 附属文件），不落库——前端回填表单后仍走 create/update 保存。 */
    /**
     * 下载技能包 zip。
     *
     * <p><b>单独发 {@code skill:export} 而不复用 {@code skill:view}</b>：查看接口返回的附属文件清单
     * 只有路径与大小（{@code SkillFileVO} 不带内容），文件字节此前在后台任何接口都拿不到。
     * 所以这不是"同一份数据换个格式"，而是一个新的数据出口——附属文件里可能有脚本与内网文档，
     * "能看到有哪些文件"和"能把整包带走"是两种能力。（内容风控那批导出复用 view，
     * 是因为那边页面上本来就能看到全部词条内容，判断标准一致、结论不同。）</p>
     *
     * <p>返回二进制而非 {@code Result} 包装：前端 {@code download()} 直接触发浏览器保存。
     * 业务异常仍按 {@code Result} 返回 JSON，前端据 content-type 区分（见 request.ts 的注释）。</p>
     */
    @SaCheckPermission("skill:export")
    @OperationLog(operation = "下载技能包", target = "ai_skill")
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        SkillExportPackage pkg = skillService.exportZip(id);
        // filename(name, UTF_8) 走 RFC 5987 的 filename*，中文包名才不会在下载时变成乱码或被截断
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(pkg.filename(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(pkg.content());
    }

    @SaCheckPermission(value = {"skill:add", "skill:edit"}, mode = SaMode.OR)
    @PostMapping("/parse-upload")
    public Result<SkillUploadParseResult> parseUpload(@RequestParam("file") MultipartFile file) {
        return Result.success(skillService.parseUploadContent(file));
    }
}
