package com.richard.fyoung.customerwork.capability.deadletter;

/**
 * 死信重投处理器（扩展点）：告诉队列"这类失败该怎么重做一遍"。
 *
 * <p>队列本身不可能知道怎么重投一次工具调用或一条通知——它只有载荷。业务方按类型注册实现，
 * 队列按 {@link #type()} 分发。没有注册处理器的类型会被跳过并记 error，
 * 而不是反复空转（那会让队列看起来在工作、实际什么都没做）。</p>
 *
 * <p><b>实现必须幂等</b>：重投的前提是"上次不确定成没成"，网络超时时下游可能其实已经执行了。
 * 不幂等的重投会把丢单换成重复下单，后者往往更难收拾。</p>
 * @author owlzhangfq@gmail.com
 */
public interface DeadLetterHandler {

    /** 处理哪一类死信；与 {@link DeadLetter#getType()} 对应。 */
    String type();

    /**
     * 重投一次。
     *
     * @param letter 死信（载荷自包含）
     * @throws Exception 重投失败时抛出，队列据此累计次数并安排下一次退避
     */
    void retry(DeadLetter letter) throws Exception;
}
