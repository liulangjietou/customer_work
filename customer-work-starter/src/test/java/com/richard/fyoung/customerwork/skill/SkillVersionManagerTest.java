package com.richard.fyoung.customerwork.skill;

import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技能版本管理器单测：版本解析 / 版本比较 / 更新检测。
 * @author owlzhangfq@gmail.com
 */
class SkillVersionManagerTest {

    private SkillVersionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SkillVersionManager();
    }

    private AgentSkill skill(String name, String content) {
        return AgentSkill.builder()
            .name(name)
            .description("test")
            .skillContent(content)
            .build();
    }

    @Test
    void parseVersion_shouldExtractFromHtmlComment() {
        AgentSkill s = skill("test", "# 退款处理\n<!-- version: 1.2.0 -->\n内容");
        assertEquals("1.2.0", manager.parseVersion(s));
    }

    @Test
    void parseVersion_shouldExtractFromMarkdownHeader() {
        AgentSkill s = skill("test", "# version: 2.0.1\n退款处理");
        assertEquals("2.0.1", manager.parseVersion(s));
    }

    @Test
    void parseVersion_shouldReturnDefault_whenNoVersionTag() {
        AgentSkill s = skill("test", "# 退款处理\n普通内容");
        assertEquals("0.0.1", manager.parseVersion(s));
    }

    @Test
    void parseVersion_shouldHandleNullSkill() {
        assertEquals("0.0.1", manager.parseVersion(null));
    }

    @Test
    void registerLoaded_shouldTrackVersions() {
        List<AgentSkill> skills = List.of(
            skill("refund", "<!-- version: 1.0.0 -->\n内容"),
            skill("complaint", "# version: 2.1.0\n内容")
        );
        manager.registerLoaded(skills);

        assertEquals("1.0.0", manager.getVersion("refund"));
        assertEquals("2.1.0", manager.getVersion("complaint"));
    }

    @Test
    void checkUpdates_shouldDetectNewerVersion() {
        manager.registerLoaded(List.of(
            skill("refund", "<!-- version: 1.0.0 -->\n内容")
        ));

        // 重新加载：版本升级
        List<AgentSkill> latest = List.of(
            skill("refund", "<!-- version: 1.1.0 -->\n新内容")
        );

        List<String> updates = manager.checkUpdates(latest);
        assertEquals(1, updates.size());
        assertTrue(updates.contains("refund"));
    }

    @Test
    void checkUpdates_shouldNotReportSameVersion() {
        manager.registerLoaded(List.of(
            skill("refund", "<!-- version: 1.0.0 -->\n内容")
        ));

        List<AgentSkill> latest = List.of(
            skill("refund", "<!-- version: 1.0.0 -->\n内容（未变）")
        );

        List<String> updates = manager.checkUpdates(latest);
        assertTrue(updates.isEmpty(), "同版本不应报更新");
    }

    @Test
    void checkUpdates_shouldDetectNewSkill() {
        manager.registerLoaded(List.of(
            skill("refund", "<!-- version: 1.0.0 -->\n内容")
        ));

        List<AgentSkill> latest = List.of(
            skill("refund", "<!-- version: 1.0.0 -->\n内容"),
            skill("new-skill", "<!-- version: 0.1.0 -->\n新技能")
        );

        List<String> updates = manager.checkUpdates(latest);
        assertTrue(updates.contains("new-skill"), "新技能应报更新");
    }

    @Test
    void compareVersion_shouldCompareCorrectly() {
        assertTrue(SkillVersionManager.compareVersion("1.2.0", "1.1.0") > 0);
        assertTrue(SkillVersionManager.compareVersion("1.0.0", "1.0.1") < 0);
        assertEquals(0, SkillVersionManager.compareVersion("1.0.0", "1.0.0"));
        assertTrue(SkillVersionManager.compareVersion("2.0.0", "1.9.9") > 0);
    }
}
