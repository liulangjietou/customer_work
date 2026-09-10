package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import com.richard.fyoung.customerwork.tool.mcp.contract.McpToolChange;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次契约快照的展示视图。
 *
 * @param id          快照 ID
 * @param contractHash 规范化契约指纹
 * @param toolCount   工具数量
 * @param driftSeverity 相对上一条快照的级别：NONE / COMPATIBLE / BREAKING
 * @param changes     逐条变更；无漂移时为空
 * @param capturedAt  采集时刻
 * @author owlzhangfq@gmail.com
 */
public record McpContractSnapshotVO(Long id,
                                    String contractHash,
                                    Integer toolCount,
                                    String driftSeverity,
                                    List<McpToolChange> changes,
                                    LocalDateTime capturedAt) {
}
