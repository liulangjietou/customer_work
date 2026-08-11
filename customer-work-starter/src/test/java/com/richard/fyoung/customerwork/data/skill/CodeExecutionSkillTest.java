package com.richard.fyoung.customerwork.data.skill;

import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 代码执行技能单测（Skill 进阶，AgentScope 2.0 迁移版）。
 *
 * <p>1.x 的 {@code skillBox.codeExecution().withRead().withWrite().enable()} 流式构建在 2.0 已移除：
 * 代码执行的<b>工作目录</b>在 {@link SkillBox#setWorkDir(Path)} 设置，<b>开关</b>由
 * {@code ReActAgent.Builder.skillCodeExecutionEnabled(true)} 承接（见 {@code CustomerServiceAgentFactory}）。
 * 本测试校验 2.0 的 workDir 设置 API。</p>
 * @author owlzhangfq@gmail.com
 */
class CodeExecutionSkillTest {

    @Test
    void setWorkDir_shouldConfigureCodeExecutionWorkDir(@TempDir Path workDir) {
        SkillBox skillBox = new SkillBox(new Toolkit());

        skillBox.setWorkDir(workDir);

        assertEquals(workDir, skillBox.getCodeExecutionWorkDir(),
            "代码执行工作目录应被正确设置");
    }
}
