package com.richard.fyoung.customerwork.tool.mcp.contract;

/**
 * 契约变更的类型，以及它是否会让<b>既有调用</b>失败。
 *
 * <h3>分级的依据</h3>
 * <p>判据只有一条：<b>模型按旧契约发出的调用，在新契约下还能不能成功</b>。
 * 少一个工具、多一个必填参数、字段类型变了，旧调用当场失败；
 * 多一个工具、多一个可选字段、描述改了，旧调用照跑不误。</p>
 *
 * <p>描述变化刻意归入兼容档而不是忽略：它不会让调用失败，但会改变模型<b>选不选这个工具</b>，
 * 属于「答得对不对」而非「跑不跑得起来」。运营需要看得见，但不该和「工具没了」同级告警。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public enum McpChangeType {

    /** 工具消失：模型仍会尝试调用它，直接失败。 */
    TOOL_REMOVED(true),
    /** 新增必填字段：旧调用不带它，服务端校验不过。 */
    REQUIRED_ADDED(true),
    /** 字段消失：旧调用仍在传，可能被拒或被静默忽略。 */
    FIELD_REMOVED(true),
    /** 字段类型改变：旧调用传的值类型不再匹配。 */
    FIELD_TYPE_CHANGED(true),
    /** 入参 schema 顶层类型改变：整个参数结构变了。 */
    SCHEMA_TYPE_CHANGED(true),

    /** 新增工具：多一个能力，旧调用不受影响。 */
    TOOL_ADDED(false),
    /** 新增可选字段：不传也能跑。 */
    FIELD_ADDED(false),
    /** 必填放宽为可选：旧调用照样带着，不影响。 */
    REQUIRED_REMOVED(false),
    /** 描述改变：不影响调用能否成功，但会影响模型选不选它。 */
    DESCRIPTION_CHANGED(false);

    private final boolean breaking;

    McpChangeType(boolean breaking) {
        this.breaking = breaking;
    }

    /** 这一类变更是否会让按旧契约发出的调用失败。 */
    public boolean isBreaking() {
        return breaking;
    }
}
