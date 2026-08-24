package com.richard.fyoung.customeradmin.aiconfig.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MCP Mapper。
 * @author owlzhangfq@gmail.com
 */
public interface AiMcpMapper extends BaseMapper<AiMcp> {

    /** 生产启动门禁专用：扫描所有租户的启用项，调用方只能检查凭据存储形态。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT * FROM ai_mcp WHERE deleted = 0 AND status = 1")
    List<AiMcp> selectAllEnabledForProductionValidation();
}
