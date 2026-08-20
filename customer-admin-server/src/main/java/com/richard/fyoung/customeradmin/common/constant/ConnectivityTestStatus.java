package com.richard.fyoung.customeradmin.common.constant;

/**
 * 连通性测试结果状态（模型 / MCP / 知识库三种资源共用）。
 *
 * <p>三个 {@code XxxTestResult} 各自定义过一份完全相同的三个状态，而前端用同一段逻辑渲染它们——
 * 其中一处改了编码，那一类资源的测试结果就会显示成另一种状态。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ConnectivityTestStatus {

    /** 尚未测试过。 */
    public static final int UNTESTED = 0;

    /** 测试通过。 */
    public static final int SUCCESS = 1;

    /** 测试失败。 */
    public static final int FAILED = 2;

    private ConnectivityTestStatus() {
    }
}
