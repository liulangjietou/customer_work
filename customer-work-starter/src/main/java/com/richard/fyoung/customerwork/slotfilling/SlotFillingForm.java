package com.richard.fyoung.customerwork.slotfilling;

import lombok.Getter;

import java.util.List;

/**
 * 槽位收集表单：一组有序槽位 + 表单名。内置退货/退款表单工厂。
 * @author owlzhangfq@gmail.com
 */
@Getter
public class SlotFillingForm {

    /** 退款表单名（避免魔法值）。 */
    public static final String FORM_REFUND = "refund";

    /** 订单号正则：6 位以上数字，或 GMLOC 前缀单号。 */
    private static final String ORDER_NO_REGEX = "(GMLOC\\w+|\\d{6,})";

    private final String name;
    private final List<Slot> slots;

    public SlotFillingForm(String name, List<Slot> slots) {
        this.name = name;
        this.slots = slots;
    }

    /** 退款信息收集表单：订单号（正则抽取）+ 退款原因（自由文本追问）。 */
    public static SlotFillingForm refundForm() {
        return new SlotFillingForm(FORM_REFUND, List.of(
            Slot.withPattern("orderId", "订单号", "请提供需要退款的订单号。", ORDER_NO_REGEX),
            Slot.freeText("reason", "退款原因", "请简要说明退款原因（如：质量问题 / 七天无理由 / 拍错了）。")));
    }
}
