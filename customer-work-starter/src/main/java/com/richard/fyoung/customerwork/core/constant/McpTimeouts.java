package com.richard.fyoung.customerwork.core.constant;

import java.time.Duration;

/**
 * MCP / Higress 客户端握手与注册的硬超时。
 *
 * <p><b>为什么必须显式传超时</b>：{@code Mono.block()} 不带参数时会无限等待。MCP 注册内部先
 * {@code initialize()} 再 {@code listTools()}，一旦某个 MCP 服务握手卡住（例如服务端不支持可选的
 * SSE 长连接，导致 SDK 内部状态机卡死），调用线程会永久挂起——<b>不抛异常、不进 error 日志、
 * 不触发任何告警</b>，外层的 try/catch 根本等不到异常被抛出。表现只是"前端界面没有响应"。</p>
 *
 * <p><b>这个故障已经真实发生过一次</b>：后台工作台链路当时踩到并修复（给 block 传了超时），
 * 但它参考的来源——客服端 {@code McpToolkitConfigurer} 与 {@code HigressToolkitConfigurer}——
 * 四处 block 一直是裸的，而客服端才是 H5 终端用户真实走的那条路。两侧超时值此前也各写一遍，
 * 属于同一个概念的多个真相来源。现在统一收敛到这里，两个模块共同引用。</p>
 *
 * <p><b>取值依据</b>：MCP 握手是建连 + 一次 initialize + 一次 listTools，正常在秒级完成。
 * 10 秒足以覆盖跨机房网络抖动，又不会让用户在首条消息上等太久——注册发生在建 Agent 路径上，
 * 这段时间是直接加在首字延迟里的。单个服务超时不阻断应用启动，由调用侧的 try/catch 兜住。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class McpTimeouts {

    /** MCP 客户端构建（建连 + initialize）的硬超时。 */
    public static final Duration BUILD = Duration.ofSeconds(10);

    /** MCP 客户端注册进 Toolkit（listTools）的硬超时。 */
    public static final Duration REGISTER = Duration.ofSeconds(10);

    private McpTimeouts() {
    }
}
