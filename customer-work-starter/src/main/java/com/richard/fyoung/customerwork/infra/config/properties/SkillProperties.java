package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;
import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Skill 技能库配置。 */
@Data
public class SkillProperties {
    /** 是否启用 Skill。 */
    private boolean enabled = true;
    /**
     * 仓库类型：classpath（只读内置）| filesystem（可读写，支持技能自进化/写回）| mysql（权威落库）。
     *
     * <p>{@code mysql} 时技能存 {@code cw_skill} / {@code cw_skill_file}，启动时物化到 {@link #directory}
     * 再交框架的 FileSystemSkillRepository 读——框架只认文件系统，那个目录是每次启动重建的缓存而非权威来源，
     * 因而也一律只读挂载（写回它下次启动即被覆盖）。</p>
     */
    private String repository = "classpath";
    /** classpath 仓库的资源目录。 */
    private String location = "skills";
    /** filesystem 仓库的磁盘目录；mysql 仓库的物化目录（可随时重建，故放系统临时目录）。 */
    private String directory = RuntimeWorkDir.of("skills");
    /** filesystem 仓库是否可写（支持 Agent 沉淀 / 上传新技能）。 */
    private boolean writable = true;
    /** 是否注册"运行时加载技能"工具，允许 Agent 按需自行加载技能。 */
    private boolean runtimeLoadToolEnabled = false;
    /** 是否启用代码执行技能（注册读写/Shell 工具，使技能可执行代码）。默认关闭。 */
    private boolean codeExecutionEnabled = false;
    /** 代码执行工作目录。 */
    private String codeExecutionWorkDir = RuntimeWorkDir.of("skill-workspace");
}
