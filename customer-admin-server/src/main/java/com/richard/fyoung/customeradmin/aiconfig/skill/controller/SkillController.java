package com.richard.fyoung.customeradmin.aiconfig.skill.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillUploadParseResult;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillVO;
import com.richard.fyoung.customeradmin.aiconfig.skill.service.SkillService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
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
    @SaCheckPermission(value = {"skill:add", "skill:edit"}, mode = SaMode.OR)
    @PostMapping("/parse-upload")
    public Result<SkillUploadParseResult> parseUpload(@RequestParam("file") MultipartFile file) {
        return Result.success(skillService.parseUploadContent(file));
    }
}
