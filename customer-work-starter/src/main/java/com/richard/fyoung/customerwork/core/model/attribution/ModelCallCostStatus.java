package com.richard.fyoung.customerwork.core.model.attribution;

/**
 * 单个模型分段的结算状态。
 *
 * <p>只有 {@link #SETTLED} 才代表金额完整可用；其它状态都必须保留为空金额，不能用 0
 * 冒充“免费调用”。</p>
 */
public enum ModelCallCostStatus {

    /** 冻结价格与 usage 完整，金额已按调用时快照结算。 */
    SETTLED,
    /** 调用没有有效价格快照。 */
    UNPRICED,
    /** 价格存在，但输入或输出 token 未上报。 */
    USAGE_MISSING,
    /** usage 出现负数或缓存 token 超过输入 token。 */
    USAGE_INVALID,
    /** 工具、MCP、Skill 等非模型分段。 */
    NOT_APPLICABLE
}
