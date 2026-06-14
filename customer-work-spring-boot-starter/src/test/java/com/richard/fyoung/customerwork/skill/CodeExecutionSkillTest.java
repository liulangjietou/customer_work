package com.richard.fyoung.customerwork.skill;

import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码执行技能单测（Skill 进阶）：启用后向 toolkit 注册读写等代码执行工具。
 * @author owlzhangfq@gmail.com
 */
class CodeExecutionSkillTest {

    @Test
    void codeExecution_shouldRegisterTools(@TempDir Path workDir) {
        Toolkit toolkit = new Toolkit();
        int before = toolkit.getToolNames().size();
        SkillBox skillBox = new SkillBox(toolkit);

        skillBox.codeExecution()
            .workDir(workDir.toString())
            .withRead()
            .withWrite()
            .enable();

        assertTrue(toolkit.getToolNames().size() > before,
            "启用代码执行后应注册读写工具: " + toolkit.getToolNames());
    }
}
