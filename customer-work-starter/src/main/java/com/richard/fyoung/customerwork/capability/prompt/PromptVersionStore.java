package com.richard.fyoung.customerwork.capability.prompt;

import java.util.List;
import java.util.Optional;

/**
 * 提示词版本存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryPromptVersionStore}；{@code prompt-version.store-mode=jdbc}
 * 落 {@code cw_prompt_version} 表。</p>
 *
 * <p>语义：按 {@code fingerprint} 幂等写入——同一版提示词反复观测到（重启、多副本）只留一条，
 * 且保留<b>最早</b>那次的观测时间，那才是"这版什么时候上线的"。</p>
 * @author owlzhangfq@gmail.com
 */
public interface PromptVersionStore {

    /** 幂等记录一个版本；已存在则保留原有观测时间。 */
    void record(PromptVersion version);

    /** 按指纹查版本全文。 */
    Optional<PromptVersion> find(String fingerprint);

    /** 最近若干个版本，观测时间倒序（最新在前）。 */
    List<PromptVersion> findRecent(int limit);
}
