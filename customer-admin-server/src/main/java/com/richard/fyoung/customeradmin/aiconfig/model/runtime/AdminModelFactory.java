package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelAssetService;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.billing.service.ModelPriceService;
import com.richard.fyoung.customerwork.core.model.ChatModelProber;
import com.richard.fyoung.customerwork.core.model.attribution.AttributedModel;
import com.richard.fyoung.customerwork.core.model.attribution.ModelCallAttribution;
import com.richard.fyoung.customerwork.infra.config.ChatModelFactory;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型运行时构建（admin 薄壳）：连通性测试（{@link #testConnectivity}）+ 现场构建真实 {@link Model} 实例
 * （{@link #buildModel}，供动态智能体运行时注入 {@code ReActAgent}）。
 *
 * <p>两件事的通用能力都已下沉 starter：建模走 {@link ChatModelFactory}（九厂商静态工厂：五家原生 + GLM/DeepSeek/Kimi/MiniMax 专用 Formatter），
 * 探活走 {@link ChatModelProber}（四厂商最小探活协议 + 固定 DNS 解析结果）。本类只保留 admin 侧职责——
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
    private final ModelEndpointPolicy endpointPolicy;
    private final ModelPriceService modelPriceService;
    private final ModelConfigAccess modelConfigAccess;
    private final ModelAssetService modelAssetService;

    public AdminModelFactory() {
        this(new ModelEndpointPolicy(List::of), null);
    }

    public AdminModelFactory(ModelEndpointPolicy endpointPolicy) {
        this(endpointPolicy, null);
    }

    public AdminModelFactory(ModelEndpointPolicy endpointPolicy, ModelPriceService modelPriceService) {
        this(new ChatModelProber(endpointPolicy), endpointPolicy, modelPriceService, null, null);
    }

    /**
     * 生产装配构造。{@code modelConfigAccess} 与 {@code modelAssetService} 用于按部署 ID 解析
     * 资产登记的上下文窗口——见 {@link #resolveDeclaredContextWindow}。
     */
    @Autowired
    public AdminModelFactory(ModelEndpointPolicy endpointPolicy, ModelPriceService modelPriceService,
                             ModelConfigAccess modelConfigAccess, ModelAssetService modelAssetService) {
        this(new ChatModelProber(endpointPolicy), endpointPolicy, modelPriceService,
            modelConfigAccess, modelAssetService);
    }

    /** 包内可见，供单测注入短超时，避免真实等待默认 8s（生产走策略注入构造）。 */
    AdminModelFactory(Duration testTimeout) {
        this(testTimeout, new ModelEndpointPolicy(List::of));
    }

    private AdminModelFactory(Duration testTimeout, ModelEndpointPolicy endpointPolicy) {
        this(new ChatModelProber(testTimeout, endpointPolicy), endpointPolicy, null, null, null);
    }

    /** 包内测试构造：薄壳测试只验证委托与结果翻译。 */
    AdminModelFactory(ChatModelProber prober) {
        this(prober, new ModelEndpointPolicy(List::of), null, null, null);
    }

    /** 包内测试构造：为模型构建注入确定性的端点策略与 DNS 结果。 */
    AdminModelFactory(ChatModelProber prober, ModelEndpointPolicy endpointPolicy) {
        this(prober, endpointPolicy, null, null, null);
    }

    AdminModelFactory(ChatModelProber prober, ModelEndpointPolicy endpointPolicy,
                      ModelPriceService modelPriceService) {
        this(prober, endpointPolicy, modelPriceService, null, null);
    }

    AdminModelFactory(ChatModelProber prober, ModelEndpointPolicy endpointPolicy,
                      ModelPriceService modelPriceService, ModelConfigAccess modelConfigAccess,
                      ModelAssetService modelAssetService) {
        this.prober = prober;
        this.endpointPolicy = endpointPolicy;
        this.modelPriceService = modelPriceService;
        this.modelConfigAccess = modelConfigAccess;
        this.modelAssetService = modelAssetService;
    }

    /**
     * 按厂商发一条固定 prompt 的最小探活请求，验证连通性。未知 provider 由 {@link ModelProvider#of} fast fail；
     * 探活结果（成功与否 + 失败原因）由 {@link ChatModelProber} 产出，这里只补测试时刻并转成前端 DTO。
     */
    public ModelTestResult testConnectivity(String provider, String baseUrl, String apiKey, String modelName) {
        ModelProvider p = ModelProvider.of(provider);
        LocalDateTime now = LocalDateTime.now();
        ChatModelProber.ProbeResult result = prober.probe(p.getCode(), baseUrl, apiKey, modelName);
        int status = result.success() ? ConnectivityTestStatus.SUCCESS : ConnectivityTestStatus.FAILED;
        return new ModelTestResult(status, now, result.message());
    }

    /**
     * 构建可直接注入 {@code ReActAgent.Builder#model(Model)} 的真实模型实例（动态智能体运行时用，
     * 区别于 {@link #testConnectivity}——后者是短生命周期的连通性探测请求）。
     *
     * <p>各厂商全部走框架原生 ChatModel（由 {@link ChatModelFactory} 统一构建）；未知 provider 由
     * {@link ModelProvider#of} fast fail，不会落到工厂的默认厂商分支。高级生成参数（温度/maxTokens）
     * 交给模型默认值，与 {@code ModelSaveRequest} 不暴露调参保持一致。构建前会重新校验当次运行时
     * baseUrl，阻断已发布配置绕过保存期校验；该校验不替代厂商 SDK 自身连接阶段的 DNS 与重定向控制。</p>
     */
    public Model buildModel(String provider, String baseUrl, String apiKey, String modelName) {
        return buildModelWithWindow(provider, baseUrl, apiKey, modelName, null);
    }

    /**
     * 同上，并把权威上下文窗口注入运行时，覆盖框架的模型名推断。
     *
     * <p>框架的推断表只收录各厂商官方模型名；{@code glm} / {@code deepseek} 这类第三方模型走
     * OpenAI 兼容协议接入时一律推断为 0，而下游会把 0 当成「窗口为零」而不是「未知」——
     * 路由按各档取 min 上报能力、上线认证按窗口判门槛，都会因此失真。已经持有资产对象的调用方
     * 直接传声明值；只有部署 ID 的调用方走 {@link #buildModel(String, String, String, String, Long)}，
     * 由本类自行解析。</p>
     *
     * @param contextWindowSize 权威上下文窗口（可空；空或非正数则回落框架推断）
     */
    public Model buildModelWithWindow(String provider, String baseUrl, String apiKey, String modelName,
                                      Integer contextWindowSize) {
        ModelProvider p = ModelProvider.of(provider);
        String validatedBaseUrl = endpointPolicy.validateAndNormalizeBaseUrl(baseUrl);
        return ChatModelFactory.build(p.getCode(), modelName, apiKey, validatedBaseUrl, true,
            GenerateOptions.builder().build(), null, null, contextWindowSize);
    }

    /**
     * 按部署建模：在计费归因之外，同时把该部署对应资产登记的上下文窗口注入运行时。
     *
     * <p>窗口在这里解析而不是让调用方各传各的——建模路径不止一条（主备模型、路由策略候选、
     * 知识库、认证探测），逐个要求「记得传窗口」必然漏，漏了还不报错，只表现为运行时能力上报为 0。</p>
     */
    public Model buildModel(String provider, String baseUrl, String apiKey,
                            String modelName, Long deploymentId) {
        Model model = buildModelWithWindow(provider, baseUrl, apiKey, modelName,
            resolveDeclaredContextWindow(deploymentId));
        ModelCallAttribution attribution = modelPriceService == null
            ? ModelCallAttribution.unpriced(provider, deploymentId, modelName)
            : modelPriceService.attribution(provider, deploymentId, modelName);
        return new AttributedModel(model, attribution);
    }

    /**
     * 按部署 ID 解析资产登记的上下文窗口；依赖未装配、部署不可见或资产未登记窗口时返回 {@code null}，
     * 由调用方回落框架推断。窗口只是能力元信息，解析不到不该阻断建模。
     */
    public Integer resolveDeclaredContextWindow(Long deploymentId) {
        if (deploymentId == null || modelConfigAccess == null || modelAssetService == null) {
            return null;
        }
        AiModelConfig deployment = modelConfigAccess.findVisibleAnyStateById(deploymentId);
        return deployment == null ? null : modelAssetService.findDeclaredContextWindow(deployment);
    }
}
