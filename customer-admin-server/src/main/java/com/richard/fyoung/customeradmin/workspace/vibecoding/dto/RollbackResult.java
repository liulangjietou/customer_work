package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import java.util.List;

/**
 * 会话一键回滚结果：把 workspace 恢复到 baseline 后，被恢复的已跟踪文件与被清理的新增文件清单。
 *
 * @param restoredFiles 已跟踪文件恢复到 baseline 内容（本会话内的修改/删除被还原）的相对路径清单
 * @param deletedFiles  本会话新增的未跟踪文件被清理删除的相对路径清单
 * @author owlzhangfq@gmail.com
 */
public record RollbackResult(List<String> restoredFiles, List<String> deletedFiles) {
}
