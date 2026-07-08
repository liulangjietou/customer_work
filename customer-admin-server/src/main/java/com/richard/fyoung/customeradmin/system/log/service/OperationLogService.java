package com.richard.fyoung.customeradmin.system.log.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 操作日志查询（只读，无增删改——需求文档只要求记录与查看）。
 * @author owlzhangfq@gmail.com
 */
@Service
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogService(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /** {@code PageQuery.keyword} 匹配 username；{@code PageQuery.status} 复用为 result 过滤（1成功/0失败）。 */
    public PageResult<SysOperationLog> page(PageQuery query) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(SysOperationLog::getUsername, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysOperationLog::getResult, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), SysOperationLog::getCreateTime);

        IPage<SysOperationLog> page = operationLogMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page);
    }
}
