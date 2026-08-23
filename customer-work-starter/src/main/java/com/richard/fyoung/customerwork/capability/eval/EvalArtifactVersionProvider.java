package com.richard.fyoung.customerwork.capability.eval;

/** 采集一次评测实际使用的模型、提示词、Agent、知识、工具、Judge 与 rubric 版本。 */
@FunctionalInterface
public interface EvalArtifactVersionProvider {

    EvalVersionBinding capture(EvalType type, String promptVersion);
}
