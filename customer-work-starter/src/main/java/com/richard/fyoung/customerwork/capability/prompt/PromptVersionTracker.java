package com.richard.fyoung.customerwork.capability.prompt;

import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 提示词版本追踪器：把"当前跑的是哪一版提示词"变成一个可查询、可关联的事实。
 *
 * <p>B4 的配置版本化记的是"这次<b>发布</b>下发了什么"，而本类记的是"运行时<b>实际生效</b>的是什么"。
 * 两者并不总是一致——灰度只发给部分租户、实例还没收到推送、有人直接改了 Nacos 而没走发布流程，
 * 都会造成偏差。做效果归因时，能对上号的只能是后者。</p>
 *
 * <p>与评测的关系：每次评测运行都记下当时的提示词指纹。于是"指标掉了"这件事可以被拆成两问——
 * 指纹变了吗？变了，那就是这次改动引入的，去比两版全文；没变，那不必再对着提示词逐字看，
 * 该去查模型或数据。这就是归因。</p>
 * @author owlzhangfq@gmail.com
 */
public class PromptVersionTracker {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionTracker.class);

    private final CustomerServiceAgentFactory agentFactory;
    private final PromptVersionStore store;

    /** 上次观测到的指纹，用于只在变化时打日志——提示词不常改，每次评测都打一行纯属噪声。 */
    private volatile String lastFingerprint = "";

    public PromptVersionTracker(CustomerServiceAgentFactory agentFactory, PromptVersionStore store) {
        this.agentFactory = agentFactory;
        this.store = store;
    }

    /**
     * 采集当前生效的提示词版本并留痕。
     *
     * <p>失败返回空指纹而不抛出：版本留痕是旁路，不该让评测或对话因此中断。</p>
     *
     * @return 当前提示词指纹；取不到返回空串
     */
    public String captureCurrent() {
        try {
            String prompt = agentFactory.systemPrompt();
            PromptVersion version = PromptVersion.of(prompt, System.currentTimeMillis());
            if (version.fingerprint().isEmpty()) {
                return "";
            }
            store.record(version);
            if (!version.fingerprint().equals(lastFingerprint)) {
                log.info("prompt version changed: {} -> {}, length={}",
                    lastFingerprint.isEmpty() ? "(none)" : lastFingerprint,
                    version.fingerprint(), version.length());
                lastFingerprint = version.fingerprint();
            }
            return version.fingerprint();
        } catch (Exception e) {
            log.error("capture prompt version failed, errorCode={}", "PROMPT-VERSION-CAPTURE-FAIL", e);
            return "";
        }
    }

    /** 按指纹取版本全文（归因时比对两版差异用）。 */
    public Optional<PromptVersion> find(String fingerprint) {
        return store.find(fingerprint);
    }

    /** 最近若干个版本，观测时间倒序。 */
    public List<PromptVersion> recent(int limit) {
        return store.findRecent(limit);
    }
}
