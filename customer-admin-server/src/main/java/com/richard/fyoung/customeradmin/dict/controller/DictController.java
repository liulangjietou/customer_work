package com.richard.fyoung.customeradmin.dict.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.dict.dto.DictItemSaveRequest;
import com.richard.fyoung.customeradmin.dict.dto.DictItemVO;
import com.richard.fyoung.customeradmin.dict.dto.DictOptionVO;
import com.richard.fyoung.customeradmin.dict.dto.DictTypeSaveRequest;
import com.richard.fyoung.customeradmin.dict.dto.DictTypeVO;
import com.richard.fyoung.customeradmin.dict.service.DictService;
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

import java.util.List;

/**
 * 字典管理：类型 + 字典项两级 CRUD，及消费端下拉选项查询。
 *
 * <p>管理接口按 {@code dict:*} 权限点控制；{@code /options/*} 是各业务页面下拉的消费入口，
 * 仅要求登录（能进后台的人即可读字典选项，与菜单权限解耦——否则每接一个页面都要给角色补授权）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/dict")
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    // ---------- 字典类型 ----------

    @SaCheckPermission("dict:view")
    @GetMapping("/types")
    public Result<List<DictTypeVO>> listTypes() {
        return Result.success(dictService.listTypes());
    }

    @SaCheckPermission("dict:add")
    @OperationLog(operation = "新增字典类型", target = "cw_dict_type")
    @PostMapping("/types")
    public Result<Void> createType(@Valid @RequestBody DictTypeSaveRequest request) {
        dictService.createType(request);
        return Result.success();
    }

    @SaCheckPermission("dict:edit")
    @OperationLog(operation = "编辑字典类型", target = "cw_dict_type")
    @PutMapping("/types/{id}")
    public Result<Void> updateType(@PathVariable Long id, @Valid @RequestBody DictTypeSaveRequest request) {
        dictService.updateType(id, request);
        return Result.success();
    }

    @SaCheckPermission("dict:delete")
    @OperationLog(operation = "删除字典类型", target = "cw_dict_type")
    @DeleteMapping("/types/{id}")
    public Result<Void> deleteType(@PathVariable Long id) {
        dictService.deleteType(id);
        return Result.success();
    }

    // ---------- 字典项 ----------

    @SaCheckPermission("dict:view")
    @GetMapping("/items")
    public Result<List<DictItemVO>> listItems(@RequestParam String dictType) {
        return Result.success(dictService.listItems(dictType));
    }

    @SaCheckPermission("dict:add")
    @OperationLog(operation = "新增字典项", target = "cw_dict_item")
    @PostMapping("/items")
    public Result<Void> createItem(@RequestParam String dictType, @Valid @RequestBody DictItemSaveRequest request) {
        dictService.createItem(dictType, request);
        return Result.success();
    }

    @SaCheckPermission("dict:edit")
    @OperationLog(operation = "编辑字典项", target = "cw_dict_item")
    @PutMapping("/items/{id}")
    public Result<Void> updateItem(@PathVariable Long id, @Valid @RequestBody DictItemSaveRequest request) {
        dictService.updateItem(id, request);
        return Result.success();
    }

    @SaCheckPermission("dict:delete")
    @OperationLog(operation = "删除字典项", target = "cw_dict_item")
    @DeleteMapping("/items/{id}")
    public Result<Void> deleteItem(@PathVariable Long id) {
        dictService.deleteItem(id);
        return Result.success();
    }

    // ---------- 消费端 ----------

    /** 业务页面下拉选项：仅启用项；类型停用/不存在返回空列表（前端按无字典配置降级到硬编码兜底）。 */
    @SaCheckLogin
    @GetMapping("/options/{dictType}")
    public Result<List<DictOptionVO>> options(@PathVariable String dictType) {
        return Result.success(dictService.options(dictType));
    }
}
