package com.richard.fyoung.customerwork.security;

import java.util.List;

/**
 * {@link ToolCallRiskDetector} 的判定规则（纯 POJO，不绑定任何配置框架）：调用方把自己的配置源
 * （Spring {@code @ConfigurationProperties}、数据库或常量）绑成本记录传入，判定算法只认这份规则。
 *
 * <p>正则一律按不区分大小写编译；关键字按小写子串匹配。所有列表为 {@code null} 时按空列表处理
 * （即该类规则整体不生效），阈值不做校验——配置合法性由调用方保证，判定侧不重复防御。</p>
 *
 * @param destructivePatterns        破坏性命令正则：命中即最高风险（护栏直接改写的那一档）
 * @param confirmableCommandPatterns 需人工确认的"非只读命令"正则：比破坏性清单更宽
 * @param dependencyFilePatterns     依赖/构建文件路径正则：写类工具命中即视为改依赖
 * @param batchModifyThreshold       单轮写类工具数阈值，严格大于该值即判定为批量修改风险
 * @param execToolKeywords           命令执行类工具名关键字（不区分大小写子串）
 * @param readonlyToolPatterns       强制视为只读的工具名正则白名单（命中则不判 mutating）
 * @param mutatingToolPatterns       强制视为 mutating 的工具名正则黑名单（优先级最高）
 * @author owlzhangfq@gmail.com
 */
public record ToolCallRiskRules(
    List<String> destructivePatterns,
    List<String> confirmableCommandPatterns,
    List<String> dependencyFilePatterns,
    int batchModifyThreshold,
    List<String> execToolKeywords,
    List<String> readonlyToolPatterns,
    List<String> mutatingToolPatterns) {

    public ToolCallRiskRules {
        destructivePatterns = nullToEmpty(destructivePatterns);
        confirmableCommandPatterns = nullToEmpty(confirmableCommandPatterns);
        dependencyFilePatterns = nullToEmpty(dependencyFilePatterns);
        execToolKeywords = nullToEmpty(execToolKeywords);
        readonlyToolPatterns = nullToEmpty(readonlyToolPatterns);
        mutatingToolPatterns = nullToEmpty(mutatingToolPatterns);
    }

    private static List<String> nullToEmpty(List<String> raw) {
        return raw == null ? List.of() : raw;
    }
}
