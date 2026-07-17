package com.richard.fyoung.customerwork.routing;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 坐席（人工客服）领域对象（不可变值对象）。
 *
 * <p>承载技能标签、负载与在线状态，供 {@link SeatRoutingScorer} 做确定性打分。{@code skills} 归一为小写，
 * 使技能匹配大小写不敏感；{@code id} 仅 JDBC 实现回填，进程内实现可为 {@code null}。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SeatAgent {

    private final String id;
    private final String name;
    private final Set<String> skills;
    private final int maxLoad;
    private final int currentLoad;
    private final boolean online;
    private final String group;

    public SeatAgent(String id, String name, Set<String> skills, int maxLoad,
                     int currentLoad, boolean online, String group) {
        this.id = id;
        this.name = name;
        this.skills = normalizeSkills(skills);
        this.maxLoad = maxLoad;
        this.currentLoad = currentLoad;
        this.online = online;
        this.group = group;
    }

    /** 技能标签归一：去空、trim、小写，保持插入序去重（匹配大小写不敏感）。 */
    private static Set<String> normalizeSkills(Set<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.trim().isEmpty()) {
                    result.add(s.trim().toLowerCase());
                }
            }
        }
        return result;
    }

    /** 是否具备某项技能（大小写不敏感）。 */
    public boolean hasSkill(String skill) {
        return skill != null && !skill.trim().isEmpty() && skills.contains(skill.trim().toLowerCase());
    }

    /** 是否已满负载（无空闲工位）。 */
    public boolean isOverloaded() {
        return maxLoad > 0 && currentLoad >= maxLoad;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<String> getSkills() {
        return skills;
    }

    public int getMaxLoad() {
        return maxLoad;
    }

    public int getCurrentLoad() {
        return currentLoad;
    }

    public boolean isOnline() {
        return online;
    }

    public String getGroup() {
        return group;
    }
}
