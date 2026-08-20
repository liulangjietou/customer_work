package com.richard.fyoung.customerwork.tool;

/**
 * 工具层公共字面量。
 *
 * @author owlzhangfq@gmail.com
 */
public final class ToolConstants {

    /**
     * 工具内部发起的操作所用的会话标识占位。
     *
     * <p>@Tool 方法拿不到真实会话（框架不透传），落审批单/转人工单时用它占位。
     * 这个值会写进业务表并被运营检索，两处工具写得不一样就会漏查。</p>
     */
    public static final String AGENT_TOOL_SESSION = "agent-tool";

    private ToolConstants() {
    }
}
