package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import java.util.List;

/**
 * 工作区文件目录树节点。
 *
 * @param name         文件/目录名称
 * @param relativePath 相对于会话 workspace 根目录（{@code sessions/{sessionId}/}）的相对路径
 * @param directory    是否为目录
 * @param children     子节点列表（文件节点为空列表）
 * @author owlzhangfq@gmail.com
 */
public record WorkspaceFileNode(String name, String relativePath, boolean directory, List<WorkspaceFileNode> children) {
}
