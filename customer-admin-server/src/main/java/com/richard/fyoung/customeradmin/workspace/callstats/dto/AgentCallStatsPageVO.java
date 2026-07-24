package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页返回体：{@code {total, rows}}（与前端契约一致，不用通用 PageResult 的 {pageNum,pageSize,list} 形状）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallStatsPageVO {

    private long total;
    private List<AgentCallStatsRowVO> rows;

    public AgentCallStatsPageVO(long total, List<AgentCallStatsRowVO> rows) {
        this.total = total;
        this.rows = rows;
    }
}
