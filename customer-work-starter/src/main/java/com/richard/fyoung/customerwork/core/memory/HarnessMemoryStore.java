package com.richard.fyoung.customerwork.core.memory;

import java.util.Optional;

/**
 * Harness 分层记忆的权威存储 SPI（{@code HarnessAgent} 的 {@code MEMORY.md}）。
 *
 * <p><b>为什么需要它</b>：框架的分层记忆只认 {@code {workspace}/MEMORY.md} 这个文件，
 * 而 workspace 在容器里、随实例销毁而丢，多副本之间还各写各的。本 SPI 把权威副本挪进 MySQL，
 * workspace 里那份退化成构建实例时水合出来、可随时重建的工作副本——同步两边由
 * {@link HarnessMemorySyncService} 负责。手法与 admin-server 的 {@code AgentMemoryStore} 一致，
 * 但两者键空间不同（那边按 agentCode，这边按 workspace 目录），故各自建表而不强行合并。</p>
 *
 * <p>两种实现，由 {@link HarnessMemoryStoreConfig} 按 {@code customer-work.harness.memory-store-mode} 选型：
 * {@code jdbc}（默认，{@code cw_harness_memory} 表）| {@code memory}（进程内，离线可测）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface HarnessMemoryStore {

    /** 读取某 workspace 的记忆全文；从未保存过返回 {@link Optional#empty()}。 */
    Optional<String> load(String scopeId);

    /** 覆盖保存某 workspace 的记忆全文。 */
    void save(String scopeId, String content);

    /** 删除某 workspace 的记忆。 */
    void delete(String scopeId);
}
