package com.richard.fyoung.customerwork.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 进阶单测：filesystem 可写仓库支持技能写回（自进化），并能重新加载。
 * @author owlzhangfq@gmail.com
 */
class FileSystemSkillRepositoryTest {

    @Test
    void saveThenReload_shouldPersistSkill(@TempDir Path dir) {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(dir, true);

        AgentSkill skill = AgentSkill.builder()
            .name("escalation")
            .description("投诉升级处理技能")
            .skillContent("# 升级\n当用户连续两轮不满意时转人工。")
            .build();
        boolean saved = repo.save(List.of(skill), true);
        assertTrue(saved, "可写仓库应保存成功");

        // 用新仓库实例从同目录重新加载，验证已落盘
        List<String> names = new FileSystemSkillRepository(dir, true).getAllSkillNames();
        assertTrue(names.stream().anyMatch(n -> n.contains("escalation")),
            "应能重新加载已写回的技能: " + names);
    }

    @Test
    void registerSkillLoadTool_shouldExposeRuntimeLoadTool() {
        Toolkit toolkit = new Toolkit();
        int before = toolkit.getToolNames().size();
        SkillBox skillBox = new SkillBox(toolkit);

        skillBox.registerSkillLoadTool();

        assertTrue(toolkit.getToolNames().size() > before,
            "注册运行时加载技能工具后，toolkit 应新增工具: " + toolkit.getToolNames());
    }
}
