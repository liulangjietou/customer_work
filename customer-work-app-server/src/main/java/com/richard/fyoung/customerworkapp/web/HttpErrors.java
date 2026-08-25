package com.richard.fyoung.customerworkapp.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

/**
 * 领域异常到 HTTP 状态码的映射——工单 / 审批 / 转人工三条链路共用一处口径。
 *
 * <p>这段映射此前在 4 个 Controller 里各写一遍，且已经漂移出两个版本
 * （两份带 {@code ResponseStatusException} 直通守卫、两份不带；行为上等价，
 * 因为它既不是 {@code NoSuchElementException} 也不是 {@code IllegalStateException}，
 * 本来就会走到末尾原样返回——但两个版本并存本身就说明有人抄过去时改了半截）。</p>
 *
 * <p><b>不是所有 {@code IllegalStateException} 都该映射成 409</b>：
 * {@code AgentOrderController#mapError} 刻意把它映射成 503 加一句固定文案
 * （"订单系统暂时不可用"），因为那条链路的 IllegalState 表达的是下游不可用而不是状态冲突。
 * 那是另一个概念，刻意不并入本类——合并会让订单接口在下游抖动时返回 409，
 * 而客户端对 409 的处置是"刷新重试"，对 503 才是"稍后重试"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class HttpErrors {

    private HttpErrors() {
    }

    /**
     * 把领域异常翻译成带 HTTP 语义的异常；已经是 {@link ResponseStatusException} 的原样返回。
     *
     * <ul>
     *   <li>{@link NoSuchElementException} → 404，实体不存在</li>
     *   <li>{@link IllegalStateException} → 409，状态机拒绝本次流转（如工单已被别的坐席抢走）</li>
     *   <li>其余原样抛出，交给框架按 500 处理</li>
     * </ul>
     */
    public static Throwable translate(Throwable e) {
        if (e instanceof ResponseStatusException) {
            return e;
        }
        if (e instanceof NoSuchElementException) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (e instanceof IllegalStateException) {
            return new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return e;
    }
}
