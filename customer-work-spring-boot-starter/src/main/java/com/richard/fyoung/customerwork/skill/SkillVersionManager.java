package com.richard.fyoung.customerwork.skill;

import io.agentscope.core.skill.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能版本管理器（P3：技能版本追踪与热更新检测）。
 *
 * <p>从技能内容（Markdown）中解析版本号（{@code <!-- version: x.y.z -->} 或
 * {@code # version: x.y.z}），追踪当前加载的版本，并支持检测版本更新。</p>
 *
 * <p>当技能文件被运维侧更新后，调用 {@link #checkUpdates} 可检测哪些技能有新版本，
 * 触发热重载（清除 SkillBox 缓存后重新加载）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SkillVersionManager {

    private static final Logger log = LoggerFactory.getLogger(SkillVersionManager.class);

    private static final Pattern VERSION_PATTERN =
        Pattern.compile("(?:<!--\\s*version:\\s*|#\\s*version:\\s*)(\\d+\\.\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);

    /** 默认版本号（未标注版本时使用）。 */
    public static final String DEFAULT_VERSION = "0.0.1";

    /** 已加载技能版本：skillName -> version。 */
    private final Map<String, String> loadedVersions = new HashMap<>();

    /**
     * 登记已加载的技能及其版本。
     *
     * @param skills 已加载的技能列表
     */
    public void registerLoaded(List<AgentSkill> skills) {
        loadedVersions.clear();
        for (AgentSkill skill : skills) {
            String name = skill.getName();
            String version = parseVersion(skill);
            loadedVersions.put(name, version);
            log.info("[SkillVersion] registered: {} v{}", name, version);
        }
    }

    /**
     * 检测技能列表中哪些有版本更新。
     *
     * @param latestSkills 最新的技能列表（从仓库重新加载）
     * @return 有版本更新的技能名列表
     */
    public List<String> checkUpdates(List<AgentSkill> latestSkills) {
        return latestSkills.stream()
            .filter(skill -> {
                String latestVersion = parseVersion(skill);
                String loadedVersion = loadedVersions.get(skill.getName());
                return loadedVersion == null || compareVersion(latestVersion, loadedVersion) > 0;
            })
            .map(AgentSkill::getName)
            .toList();
    }

    /** 从技能内容中解析版本号。 */
    public String parseVersion(AgentSkill skill) {
        if (skill == null) {
            return DEFAULT_VERSION;
        }
        String content = skill.getSkillContent();
        if (content == null || content.isBlank()) {
            return DEFAULT_VERSION;
        }
        Matcher m = VERSION_PATTERN.matcher(content);
        return m.find() ? m.group(1) : DEFAULT_VERSION;
    }

    /** 获取已加载的技能版本。 */
    public String getVersion(String skillName) {
        return loadedVersions.getOrDefault(skillName, DEFAULT_VERSION);
    }

    /** 获取所有已加载技能的版本快照。 */
    public Map<String, String> getLoadedVersions() {
        return Map.copyOf(loadedVersions);
    }

    /**
     * 比较语义化版本号。
     *
     * @return 正数表示 v1 > v2，负数表示 v1 < v2，0 表示相等
     */
    public static int compareVersion(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) {
                return p1 - p2;
            }
        }
        return 0;
    }
}
