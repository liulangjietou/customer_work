package com.example.customerwork.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 技能库单测（进阶「Skill」）：从 classpath 的 skills 目录加载 Markdown 技能，
 * 并注册进 SkillBox。
 */
class SkillLoadingTest {

    @Test
    void classpathRepository_shouldLoadRefundSkill() throws Exception {
        ClasspathSkillRepository repo = new ClasspathSkillRepository("skills");
        assertFalse(repo.getAllSkills().isEmpty(), "应从 classpath:skills 加载到技能");
        assertTrue(repo.getAllSkillNames().stream().anyMatch(n -> n.contains("refund")),
            "应包含退款处理技能，实际: " + repo.getAllSkillNames());
    }

    @Test
    void skillBox_shouldRegisterLoadedSkills() throws Exception {
        ClasspathSkillRepository repo = new ClasspathSkillRepository("skills");
        SkillBox skillBox = new SkillBox(new Toolkit());
        for (AgentSkill skill : repo.getAllSkills()) {
            skillBox.registerSkill(skill);
        }
        assertFalse(skillBox.getAllSkillIds().isEmpty(), "SkillBox 应注册到技能");
    }
}
