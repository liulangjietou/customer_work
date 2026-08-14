package com.richard.fyoung.customeradmin.eval.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientProperties;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 评测触发客户端：把后台的"立即评测"转成对客服端（8080）的一次调用。
 *
 * <p><b>为什么必须绕到 8080 去跑</b>：评测要评的是线上真实在跑的那一套——同一个 orchestrator、
 * 同一份提示词、同一条模型链。admin 侧自己实现一遍等价逻辑去评，评的就不是线上那个东西了，
 * 指标再好看也不作数。admin 因此只做触发与展示，不持有任何评测逻辑。</p>
 *
 * <p>身份走 {@code X-API-Key}（运营方）而非坐席令牌：评测是对整套系统的操作，与具体客服无关。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class EvalTriggerClient {

    private static final Logger log = LoggerFactory.getLogger(EvalTriggerClient.class);

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String INTENT_PATH = "/api/customer/eval/intent";
    private static final String QUALITY_PATH = "/api/customer/eval/quality";
    private static final String TRIGGER_SOURCE_MANUAL = "MANUAL";

    /**
     * 评测专用读超时（毫秒）。
     *
     * <p>比工单接口的 30s 长得多：质量评测要逐条生成回复再逐条 Judge 打分，一轮几分钟很正常。
     * 沿用工单的超时会让每次质量评测都在客户端侧超时中断，而 8080 那边其实还在跑、还在烧 token。</p>
     */
    private static final int EVAL_READ_TIMEOUT_MS = 300_000;

    private final RestClient restClient;
    private final String baseUrl;

    public EvalTriggerClient(CustomerWorkClientProperties properties) {
        this.baseUrl = properties.getBaseUrl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(EVAL_READ_TIMEOUT_MS);

        RestClient.Builder builder = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .requestFactory(factory);
        // 未开启鉴权时 apiKey 为空，此时不带头；带一个空值头反而会被 8080 判成"提供了错误的 key"
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.defaultHeader(API_KEY_HEADER, properties.getApiKey());
        }
        this.restClient = builder.build();
    }

    /** 触发一次评测，返回本次运行与上一版的对比。 */
    public EvalComparison trigger(EvalType type, String remark) {
        String path = type == EvalType.QUALITY ? QUALITY_PATH : INTENT_PATH;
        try {
            return restClient.post()
                .uri(uriBuilder -> uriBuilder.path(path)
                    .queryParam("trigger", TRIGGER_SOURCE_MANUAL)
                    .queryParam("remark", remark == null ? "" : remark)
                    .build())
                .retrieve()
                .body(EvalComparison.class);
        } catch (Exception e) {
            log.error("trigger eval failed, code={}, type={}, baseUrl={}",
                "EVAL-TRIGGER-FAIL", type, baseUrl, e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "触发评测失败（评测跑在客服端 " + baseUrl + "）：" + e.getMessage());
        }
    }
}
