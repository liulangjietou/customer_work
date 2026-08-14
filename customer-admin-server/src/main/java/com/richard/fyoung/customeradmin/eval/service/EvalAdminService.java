package com.richard.fyoung.customeradmin.eval.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customeradmin.eval.config.EvalTriggerClient;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalRunStore;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 评测后台服务：读客服端库的运行记录、触发新一轮评测。
 *
 * <p>本类刻意只有"读"和"转发触发"两件事，不含任何评分或对比算法——那些都在 starter 的
 * {@link EvalComparison} 与 {@code EvalService} 里。后台再实现一遍等价逻辑，两边迟早对同一批数据
 * 给出不同结论，而运营看到的是后台这一份，排查时却对着客服端那一份，很难发现。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class EvalAdminService {

    private final EvalGatewayProvider gatewayProvider;
    private final EvalTriggerClient triggerClient;

    public EvalAdminService(EvalGatewayProvider gatewayProvider, EvalTriggerClient triggerClient) {
        this.gatewayProvider = gatewayProvider;
        this.triggerClient = triggerClient;
    }

    /** 某类型最近若干次运行（时间倒序），用于趋势线与列表。 */
    public List<EvalRun> recent(EvalType type, int limit) {
        return gatewayProvider.get().findRecent(type, limit);
    }

    /** 单次运行详情（含失败明细与完整原始指标）。 */
    public EvalRun detail(String runId) {
        return gatewayProvider.get().find(runId)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "评测运行记录不存在：" + runId));
    }

    /**
     * 某次运行与它上一版的对比。
     *
     * <p>对比逻辑直接复用 starter 的 {@link EvalComparison#of}，后台不重算。</p>
     */
    public EvalComparison comparison(String runId) {
        EvalRunStore store = gatewayProvider.get();
        EvalRun current = store.find(runId)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "评测运行记录不存在：" + runId));
        return EvalComparison.of(current,
            store.findBaseline(current.evalType(), current.runId()).orElse(null));
    }

    /** 触发一次评测（转发到客服端执行），返回本次与上一版的对比。 */
    public EvalComparison trigger(EvalType type, String remark) {
        return triggerClient.trigger(type, remark);
    }
}
