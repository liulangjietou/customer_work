package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Skill 技能库配置。 */
@Data
public class SkillProperties {
    /** 是否启用 Skill。 */
    private boolean enabled = true;
    /** 仓库类型：classpath（只读内置）| filesystem（可读写，支持技能自进化/写回）。 */
    private String repository = "classpath";
    /** classpath 仓库的资源目录。 */
    private String location = "skills";
    /** filesystem 仓库的磁盘目录。 */
    private String directory = "./data/skills";
    /** filesystem 仓库是否可写（支持 Agent 沉淀 / 上传新技能）。 */
    private boolean writable = true;
    /** 是否注册"运行时加载技能"工具，允许 Agent 按需自行加载技能。 */
    private boolean runtimeLoadToolEnabled = false;
    /** 是否启用代码执行技能（注册读写/Shell 工具，使技能可执行代码）。默认关闭。 */
    private boolean codeExecutionEnabled = false;
    /** 代码执行工作目录。 */
    private String codeExecutionWorkDir = "./data/skill-workspace";
}
