package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.capability.eval.EvalArtifactVersionProvider;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersionTracker;
import com.richard.fyoung.customerwork.infra.config.NacosRuntimeConfigService;
import org.springframework.beans.factory.ObjectProvider;

/** 默认从运行中配置、提示词追踪器与评测版本提供器采集调用谱系。 */
final class ConfigurationAgentCallLineageProvider implements AgentCallLineageProvider {

    private final ObjectProvider<PromptVersionTracker> promptTrackerProvider;
    private final ObjectProvider<EvalArtifactVersionProvider> artifactVersionProvider;
    private final ObjectProvider<NacosRuntimeConfigService> runtimeConfigServiceProvider;

    ConfigurationAgentCallLineageProvider(
        ObjectProvider<PromptVersionTracker> promptTrackerProvider,
        ObjectProvider<EvalArtifactVersionProvider> artifactVersionProvider,
        ObjectProvider<NacosRuntimeConfigService> runtimeConfigServiceProvider) {
        this.promptTrackerProvider = promptTrackerProvider;
        this.artifactVersionProvider = artifactVersionProvider;
        this.runtimeConfigServiceProvider = runtimeConfigServiceProvider;
    }

    @Override
    public AgentCallLineage capture() {
        String promptVersion = "";
        PromptVersionTracker tracker = promptTrackerProvider == null
            ? null : promptTrackerProvider.getIfAvailable();
        if (tracker != null) {
            promptVersion = tracker.captureCurrent();
        }

        EvalVersionBinding runtimeBinding = runtimeBinding(promptVersion);
        NacosRuntimeConfigService runtimeService = runtimeConfigServiceProvider == null
            ? null : runtimeConfigServiceProvider.getIfAvailable();
        String revision = runtimeService == null ? "" : runtimeService.activeRevision();
        String contentHash = runtimeService == null ? "" : runtimeService.activeContentHash();
        return new AgentCallLineage("", revision, contentHash, runtimeBinding);
    }

    private EvalVersionBinding runtimeBinding(String promptVersion) {
        EvalArtifactVersionProvider provider = artifactVersionProvider == null
            ? null : artifactVersionProvider.getIfAvailable();
        if (provider == null) {
            return EvalVersionBinding.legacy(promptVersion);
        }
        EvalVersionBinding captured = provider.capture(EvalType.INTENT, promptVersion);
        if (captured == null) {
            return EvalVersionBinding.legacy(promptVersion);
        }
        // 在线调用只保留真正影响推理的五个版本；离线评测专属维度不能伪装成运行事实。
        return new EvalVersionBinding("", "", captured.modelVersion(), captured.promptVersion(),
            captured.agentVersion(), captured.knowledgeBaseVersion(), captured.toolVersion(), "", "");
    }
}
