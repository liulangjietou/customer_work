package com.richard.fyoung.customeradmin.common.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>错误码门禁</b>：{@link ResultCode} 的数值不得重复。
 *
 * <p><b>为什么需要这个测试</b>：{@code AI_CODING_FEATURE_DISABLED} 与 {@code QUOTA_EXCEEDED}
 * 曾同时占用 {@code 40043}。后者的注释写着"额度用尽不是权限问题也不是参数问题，<b>单独发码</b>，
 * 前端据此给'稍后再试'而不是'联系管理员开权限'"——而前端 {@code sse.ts} 正是按
 * {@code code === 40043} 判定额度用尽的。撞值让"单独发码"这件事当场失效：
 * 后端返回"该 AI 编码能力未开启"时，前端会把它当成额度用尽来提示。</p>
 *
 * <p>枚举撞值编译不报错（两个常量各自合法）、单测也照不出来（没人会去比两个不相干的码），
 * 只有把全部码值放在一起数一遍才看得见。错误码是<b>对前端的契约</b>，
 * 一个码值对应一种处理分支，重复即歧义。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ResultCodeUniquenessTest {

    @Test
    @DisplayName("错误码数值唯一：同一个码不得对应两种语义")
    void codesMustBeUnique() {
        Map<Integer, List<String>> byCode = new LinkedHashMap<>();
        for (ResultCode rc : ResultCode.values()) {
            byCode.computeIfAbsent(rc.getCode(), k -> new ArrayList<>()).add(rc.name());
        }

        List<String> collisions = byCode.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> e.getKey() + " 被 " + String.join(" / ", e.getValue()) + " 同时占用")
            .toList();

        if (!collisions.isEmpty()) {
            fail("ResultCode 存在撞值，共 " + collisions.size() + " 处：\n  - "
                + String.join("\n  - ", collisions)
                + "\n错误码是对前端的契约，一个码值只能有一种语义。"
                + "\n改号时优先动前端<b>没有</b>按值取用的那一个——被前端硬编码的码值一旦变更就是契约变更。");
        }
    }

    @Test
    @DisplayName("额度用尽保持 40043：前端 sse.ts 按该值判定，改动即契约变更")
    void quotaExceededCodeIsPinnedForFrontend() {
        // customer-admin-web/src/utils/sse.ts:
        //   static readonly QUOTA_EXCEEDED = 40043
        //   isQuotaExceeded() { return this.code === SseHttpError.QUOTA_EXCEEDED }
        // 前端据此把"额度用尽"渲染成一条普通消息而不是报错弹窗。要改这个值必须同步改前端，
        // 因此把它钉在这里：单纯为了腾号而改它会直接红。
        assertEquals(40043, ResultCode.QUOTA_EXCEEDED.getCode(),
            "QUOTA_EXCEEDED 的码值被前端 sse.ts 硬编码，不能为了给别的码腾位置而改动");
    }
}
