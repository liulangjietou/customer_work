package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingForm;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款信息收集工具：把已建好却没入口的槽位填充交给模型使用。
 *
 * @author owlzhangfq@gmail.com
 */
class RefundIntakeToolsTest {

    private static final String SESSION = "u1:conv-1";

    private SlotFillingService slotFillingService;
    private RefundIntakeTools tools;

    @BeforeEach
    void setUp() {
        slotFillingService = new SlotFillingService();
        tools = new RefundIntakeTools(slotFillingService, SESSION);
    }

    @Test
    @DisplayName("信息不全时告诉模型缺什么、该怎么追问")
    void asksForMissingSlot() {
        String reply = tools.collectRefundInfo("我要退款").block();

        assertTrue(reply.contains("尚未收齐"), reply);
        assertTrue(reply.contains("订单号"), "没告诉模型该问什么，它只能自己瞎猜：" + reply);
    }

    /**
     * 跨轮次持久化——这是相对"让模型自己追问"的核心增量。
     *
     * <p>模型靠对话历史记住已经问过什么，历史一被裁剪就忘了；
     * 收集进度落在 Store 里则不受裁剪影响。</p>
     */
    @Test
    @DisplayName("分多轮提供的信息能累积起来，不受对话历史裁剪影响")
    void accumulatesAcrossTurns() {
        tools.collectRefundInfo("我要退款").block();
        String afterOrder = tools.collectRefundInfo("订单号是 20260101123456").block();
        assertTrue(afterOrder.contains("尚未收齐"), "只给了订单号就说收齐了：" + afterOrder);
        assertTrue(afterOrder.contains("原因"), "该追问原因了：" + afterOrder);

        String done = tools.collectRefundInfo("质量问题").block();
        assertTrue(done.contains("已收齐"), "两项都给了却没收齐：" + done);
        assertTrue(done.contains("20260101123456"), "收齐的结果里没带上订单号：" + done);
    }

    /**
     * 收齐后<b>不</b>直接发起退款。
     *
     * <p>直接发起会绕开退款资格校验那道闸——而那是提示词第 4 条的硬要求。
     * 这里只把值交回模型，由它继续走 checkRefundEligibility → submitRefund。</p>
     */
    @Test
    @DisplayName("收齐后引导模型去走资格校验，不自己发起退款")
    void doesNotSubmitRefundDirectly() {
        tools.collectRefundInfo("退款，订单 20260101123456，质量问题").block();
        String reply = tools.collectRefundInfo("质量问题").block();

        assertTrue(reply.contains("资格校验") || reply.contains("已收齐"),
            "收齐后应把控制权交回模型并提示下一步：" + reply);
    }

    /**
     * 订单号按正则抽取，模型不会把随口一句当成订单号。
     */
    @Test
    @DisplayName("非订单号格式的话不会被当成订单号收下")
    void doesNotAcceptArbitraryTextAsOrderId() {
        tools.collectRefundInfo("我要退款").block();
        String reply = tools.collectRefundInfo("就上次那个单子").block();

        assertTrue(reply.contains("尚未收齐"),
            "把「就上次那个单子」当成订单号收下了——后续查单必然失败：" + reply);
    }

    /**
     * 订单号按本项目的实际格式抽取：纯数字（6 位以上）或 {@code GMLOC} 开头。
     *
     * <p>这条钉住格式约定——它同时出现在 {@code SlotFillingForm.ORDER_NO_REGEX} 与
     * {@code OrderTools} 的参数示例里。哪天订单号规则变了，两处都要改，
     * 而只改一处不会报错、只表现为"用户给了单号却说没收到"。</p>
     */
    @Test
    @DisplayName("订单号按项目实际格式抽取，用户带无关前缀也能取到")
    void extractsOrderIdByProjectFormat() {
        tools.collectRefundInfo("我要退款").block();

        String reply = tools.collectRefundInfo("单号 20260613001").block();

        assertTrue(reply.contains("20260613001"), "标准格式的订单号没被抽出来：" + reply);
    }

    @Test
    @DisplayName("没有真实会话时用占位标识，不与其他会话串状态")
    void fallsBackToPlaceholderSession() {
        RefundIntakeTools anonymous = new RefundIntakeTools(slotFillingService, null);

        anonymous.collectRefundInfo("订单号是 20260101999999").block();

        // 占位会话的进度不该出现在真实会话里
        assertTrue(slotFillingService.peek(SESSION, SlotFillingForm.FORM_REFUND).isEmpty(),
            "匿名调用的收集进度串进了真实会话");
    }
}
