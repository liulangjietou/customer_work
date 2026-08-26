package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link RuntimePublishStatus} 三分判定的门禁。
 *
 * <p>{@code isAdvancing()} / {@code needsHumanAction()} / {@code isSettled()} 必须<b>互斥且穷尽</b>：
 * 每个枚举值恰好命中一个。这条断言的作用是让"新增枚举值忘了归类"当场变红——
 * 那正是 {@code BLOCKED} 出问题的形状：它落在各消费方 if 链的兜底分支里，
 * 一个当它"进行中"（无限轮询）、一个当它"失败"，谁都没意识到自己在给一个没想过的值下结论。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class RuntimePublishStatusTest {

    @Test
    @DisplayName("三分判定互斥且穷尽：每个状态恰好命中一个")
    void everyStatusFallsInExactlyOneCategory() {
        List<String> problems = new ArrayList<>();
        for (RuntimePublishStatus status : RuntimePublishStatus.values()) {
            int hits = (status.isAdvancing() ? 1 : 0)
                + (status.needsHumanAction() ? 1 : 0)
                + (status.isSettled() ? 1 : 0);
            if (hits != 1) {
                problems.add(status.name() + " 命中了 " + hits + " 个分类（应恰好 1 个）"
                    + "：isAdvancing=" + status.isAdvancing()
                    + ", needsHumanAction=" + status.needsHumanAction()
                    + ", isSettled=" + status.isSettled());
            }
        }
        if (!problems.isEmpty()) {
            fail("RuntimePublishStatus 三分判定不互斥或有遗漏：\n  - " + String.join("\n  - ", problems)
                + "\n新增枚举值时必须同时决定它属于哪一类，不能靠消费方的兜底分支替它决定。");
        }
    }

    @Test
    @DisplayName("BLOCKED 只需人工介入：既不自行推进也不算已定局")
    void blockedNeedsHumanActionOnly() {
        // 调度器的 findDueCandidates 只捞 PENDING 与租约过期的 PROCESSING，永远不会再碰 BLOCKED；
        // 出口只有 retryGateBlocked（重评）与 overrideGateBlocked（紧急豁免），都由人触发。
        assertTrue(RuntimePublishStatus.BLOCKED.needsHumanAction());
        assertFalse(RuntimePublishStatus.BLOCKED.isAdvancing(),
            "把 BLOCKED 当成'还会自己往前走'会让改进闭环无限轮询——这正是本次修复的缺陷");
        assertFalse(RuntimePublishStatus.BLOCKED.isSettled(),
            "把 BLOCKED 当成'已定局'会让重评后本可继续的发布被提前关掉");
    }

    @Test
    @DisplayName("PARTIAL 会随 ACK 到齐继续推进，不是终态")
    void partialKeepsAdvancing() {
        // RuntimePublishTaskService#refreshAckStatus 每收到一条 ACK 重算一次聚合，
        // PUBLISHED → PARTIAL → APPLIED 由实例 ACK 驱动，无需人介入。
        assertTrue(RuntimePublishStatus.PARTIAL.isAdvancing());
        assertFalse(RuntimePublishStatus.PARTIAL.isSettled());
    }

    @Test
    @DisplayName("终态集合固定为 APPLIED / SUPERSEDED / FAILED")
    void settledSetIsPinned() {
        List<RuntimePublishStatus> settled = List.of(RuntimePublishStatus.values()).stream()
            .filter(RuntimePublishStatus::isSettled)
            .toList();
        assertEquals(
            List.of(RuntimePublishStatus.APPLIED,
                RuntimePublishStatus.SUPERSEDED,
                RuntimePublishStatus.FAILED),
            settled,
            "终态集合变化会直接改变改进闭环停不停轮询、模型实验页判不判失败，改动前请确认两个消费方都跟上了");
    }
}
