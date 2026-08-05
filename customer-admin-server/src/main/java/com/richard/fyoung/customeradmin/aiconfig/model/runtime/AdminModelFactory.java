package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customerwork.config.ChatModelFactory;
import com.richard.fyoung.customerwork.model.ChatModelProber;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 模型运行时构建（admin 薄壳）：连通性测试（{@link #testConnectivity}）+ 现场构建真实 {@link Model} 实例
 * （{@link #buildModel}，供动态智能体运行时注入 {@code ReActAgent}）。
 *
 * <p>两件事的通用能力都已下沉 starter：建模走 {@link ChatModelFactory}（五厂商静态工厂），
 * 探活走 {@link ChatModelProber}（四厂商最小探活协议，JDK 内置 HttpClient）。本类只保留 admin 侧职责——
 * 用 {@link ModelProvider#of} 做 provider 合法性 fast fail 收口，并把探活结果译成前端用的
 * {@link ModelTestResult}（带测试时刻，落库到 {@code ai_model_config.test_status}）。</p>
 *
 * <p>不复用 {@code customer-work-starter} 的 {@code ModelConfig}——那是「应用启动时读 yml
 * 建一个默认 Model Bean」的模式；这里是「按 {@code ai_model_config} 任意一行现场构建」的动态场景。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminModelFactory {

    private final ChatModelProber prober;

    public AdminModelFactory() {
        this.prober = new ChatModelProber();
    }

    /** 包内可见，供单测注入短超时，避免真实等待默认 8s（生产始终走无参构造）。 */
    AdminModelFactory(Duration testTimeout) {
        this.prober = new ChatModelProber(testTimeout);
    }

    /**
     * 按厂商发一条固定 prompt 的最小探活请求，验证连通性。未知 provider 由 {@link ModelProvider#of} fast fail；
     * 探活结果（成功与否 + 失败原因）由 {@link ChatModelProber} 产出，这里只补测试时刻并转成前端 DTO。
     */
    public ModelTestResult testConnectivity(String provider, String baseUrl, String apiKey, String modelName) {
        ModelProvider p = ModelProvider.of(provider);
        LocalDateTime now = LocalDateTime.now();
        ChatModelProber.ProbeResult result = prober.probe(p.getCode(), baseUrl, apiKey, modelName);
        int status = result.success() ? ModelTestResult.STATUS_SUCCESS : ModelTestResult.STATUS_FAILED;
        return new ModelTestResult(status, now, result.message());
    }

    /**
     * 构建可直接注入 {@code ReActAgent.Builder#model(Model)} 的真实模型实例（动态智能体运行时用，
     * 区别于 {@link #testConnectivity}——后者是短生命周期的连通性探测请求）。
     *
     * <p>四家厂商全部走框架原生 ChatModel（由 {@link ChatModelFactory} 统一构建）；未知 provider 由
     * {@link ModelProvider#of} fast fail，不会落到工厂的默认厂商分支。高级生成参数（温度/maxTokens）
     * 交给模型默认值，与 {@code ModelSaveRequest} 不暴露调参保持一致。</p>
     */
    public Model buildModel(String provider, String baseUrl, String apiKey, String modelName) {
        ModelProvider p = ModelProvider.of(provider);
        return ChatModelFactory.build(p.getCode(), modelName, apiKey, baseUrl, true,
            GenerateOptions.builder().build(), null, null);
    }
}
