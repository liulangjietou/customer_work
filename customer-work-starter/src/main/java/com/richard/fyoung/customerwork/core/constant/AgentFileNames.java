package com.richard.fyoung.customerwork.core.constant;

/**
 * 智能体运行时约定的文件名。
 *
 * <p>这些名字是<b>框架与技能包格式的契约</b>而非本项目可自由更改的配置：框架只按固定文件名读取，
 * 改一处不改另一处的后果是"上传成功但加载不到"，链路全程不报错。故集中一处定义，
 * 客服端与后台共用（后台依赖 starter）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class AgentFileNames {

    /** 技能包描述文件：zip 中最浅层的这个文件所在目录即技能根目录。 */
    public static final String SKILL_MD = "SKILL.md";

    /** Harness 分层记忆的落盘文件：MySQL 为权威副本，此文件由同步服务水合/回写。 */
    public static final String MEMORY_MD = "MEMORY.md";

    private AgentFileNames() {
    }
}
