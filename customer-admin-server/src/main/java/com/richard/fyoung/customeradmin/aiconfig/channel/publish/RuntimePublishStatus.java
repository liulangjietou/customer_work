package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

/**
 * 运行时配置从本地任务到实例应用的状态。
 *
 * <p><b>"这个状态还会不会自行推进"只在本枚举回答一次</b>：消费方一律调
 * {@link #isAdvancing()} / {@link #needsHumanAction()} / {@link #isSettled()}，
 * 不要在各处再写 {@code == APPLIED || == FAILED} 这类分类
 * （同 {@code TicketStatus#isEnded()} 立过的约定）。</p>
 *
 * <p><b>为什么立这条规矩</b>：这件事此前被三个消费方各判了一遍，在 {@link #BLOCKED} 上正面冲突——
 * {@code RuntimePublishTaskMapper.findDueCandidates} 只捞 {@code PENDING} 与租约过期的
 * {@code PROCESSING}（即调度器永远不会再碰 BLOCKED）、{@code ModelExperimentEffectiveStateResolver}
 * 把它算作失败、而 {@code ImprovementCaseService#refreshPublish} 的 else 分支把它当"进行中"继续轮询。
 * 于是门禁一旦阻断发布，改进闭环就每个扫描周期捞一次、判一次、再排下一次，
 * 状态永远停在 {@code PUBLISHING}；又因为走的不是失败分支，{@code lastError} 恒为空、
 * 面板上的错误提示不显示，运营只看到"发布中"挂着不动。同一行数据在模型实验页却显示"失败"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public enum RuntimePublishStatus {

    /** 待调度：Worker 会捞起。 */
    PENDING,
    /** Worker 持租约处理中；租约过期后会被重新捞起。 */
    PROCESSING,
    /**
     * 评测门禁确定性阻断；不自动重试，等待重评或有审计的紧急豁免。
     *
     * <p>出口只有 {@code RuntimePublishTaskService#retryGateBlocked}（重评后放回 PENDING）
     * 与 {@code #overrideGateBlocked}（紧急豁免），两者都要人介入——调度器的捞取条件里没有本状态。</p>
     */
    BLOCKED,
    /** 已投递 Nacos，等待实例 ACK 聚合。 */
    PUBLISHED,
    /** 部分实例已生效：ACK 继续到齐会转 APPLIED，不是终态。 */
    PARTIAL,
    /** 全部目标实例已生效。 */
    APPLIED,
    /** 任务固化快照已被同一 Nacos 键的更新发布意图取代。 */
    SUPERSEDED,
    /** 有实例拒绝且无一生效。 */
    FAILED;

    /**
     * 还会自行往前走，不需要任何人介入。
     *
     * <p>前两个靠 Worker 轮询推进，后两个靠实例 ACK 聚合推进
     * （{@code RuntimePublishTaskService#refreshAckStatus} 每收到一条 ACK 重算一次）。
     * 调用方据此决定"要不要排下一次检查"。</p>
     */
    public boolean isAdvancing() {
        return this == PENDING || this == PROCESSING || this == PUBLISHED || this == PARTIAL;
    }

    /**
     * 卡住了，等人做决定——既不会自己往前走，也不该被当成失败草草收场。
     *
     * <p>只有 {@link #BLOCKED}。误判成"进行中"会导致无限轮询；
     * 误判成"失败"则让重评后本可继续的发布被提前关掉。</p>
     */
    public boolean needsHumanAction() {
        return this == BLOCKED;
    }

    /**
     * 已成定局，不必再看。
     *
     * <p>{@link #FAILED} 归入本类是<b>产品语义</b>上的定局（有实例拒绝且无一生效，这一轮发布判定失败）。
     * 底层 {@code updateAckStatus} 允许迟到的 ACK 把它翻成 PARTIAL/APPLIED，
     * 那属于补报而不是"它会自己好起来"，消费方不应据此继续轮询。</p>
     */
    public boolean isSettled() {
        return this == APPLIED || this == SUPERSEDED || this == FAILED;
    }
}
