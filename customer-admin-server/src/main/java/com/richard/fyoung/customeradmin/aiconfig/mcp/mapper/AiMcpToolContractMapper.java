package com.richard.fyoung.customeradmin.aiconfig.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcpToolContract;

/**
 * MCP 工具契约快照 Mapper。
 *
 * <p>「取上一条快照」用 {@code QueryWrapper} 按 id 倒序 limit 1 表达即可，不另写 SQL——
 * 本模块的跨库门面场景没有 MyBatis-Plus 拦截器，条件一律用字符串列名而非 lambda wrapper。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public interface AiMcpToolContractMapper extends BaseMapper<AiMcpToolContract> {
}
