package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>模型厂商覆盖门禁</b>：三处对「支持哪些厂商」的表述必须一致。
 *
 * <h3>守的是什么</h3>
 * <p>这件事此前有两处真相：starter 的 {@code ChatModelFactory} switch 与 admin 的
 * {@link ModelProvider} 枚举。两边不同步不会报任何错，只表现为
 * <b>一边能建、另一边登记不进去</b>——批次五给 starter 加了 GLM/DeepSeek/Kimi/MiniMax
 * 四家专用 Formatter，而 admin 枚举至今只有四家原生厂商，那四个模型在后台完全用不了；
 * Ollama 同理，本地私有化部署整个进不了 ModelOps。这个状态持续了一整个批次没人发现。</p>
 *
 * <p>现在 {@link ModelProviders#SUPPORTED} 是唯一真相，本测试断言另外两处与它一致。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ModelProviderCoverageTest {

    /** 从 ChatModelFactory 源码里抽 {@code case ModelProviders.XXX} 的厂商常量名。 */
    private static final Pattern CASE_PATTERN =
        Pattern.compile("case\\s+ModelProviders\\.([A-Z_]+)\\s*:");

    @Test
    @DisplayName("admin 枚举与支持清单完全一致")
    void adminEnumMatchesSupportedSet() {
        Set<String> enumCodes = Arrays.stream(ModelProvider.values())
            .map(ModelProvider::getCode)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> supported = new TreeSet<>(ModelProviders.SUPPORTED);

        assertEquals(supported, enumCodes,
            "admin 的 ModelProvider 枚举与 ModelProviders.SUPPORTED 不一致。\n"
                + "缺的那些厂商：starter 能建模，但后台登记不进去，前端也选不到——不报错，只是用不了。\n"
                + "多出来的：后台能登记，运行时建模会落到 default 分支，行为与登记时的预期不符。");
    }

    /**
     * 建模工厂真的能处理清单里的每一家。
     *
     * <p>光对齐两份清单还不够：清单里写了、工厂 switch 里没有对应 case 的厂商，
     * 会静默落到 {@code default}（DashScope）分支——用户在后台选了 Kimi，
     * 实际请求发去了 DashScope，而两边的清单看起来都是"支持的"。</p>
     */
    @Test
    @DisplayName("建模工厂为清单里每一家都有专门分支")
    void factoryHandlesEverySupportedProvider() throws IOException {
        String source = Files.readString(factorySource(), StandardCharsets.UTF_8);

        Set<String> handled = new TreeSet<>();
        Matcher matcher = CASE_PATTERN.matcher(source);
        while (matcher.find()) {
            handled.add(matcher.group(1));
        }
        assertTrue(handled.size() >= 5, "没从工厂源码里解析到足够的 case，正则可能失效了：" + handled);

        Set<String> missing = new TreeSet<>();
        for (String code : ModelProviders.SUPPORTED) {
            // DashScope 是 default 分支，没有显式 case，这是刻意的
            if (ModelProviders.DASHSCOPE.equals(code)) {
                continue;
            }
            if (!handled.contains(code.toUpperCase())) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            fail("以下厂商在 SUPPORTED 里，但 ChatModelFactory 没有对应分支：" + missing
                + "\n它们会静默落到 default（DashScope）——用户选了 A，请求发去了 B，两边都不报错。");
        }
    }

    @Test
    @DisplayName("本地部署不要求 API Key，其余厂商要求")
    void localDeploymentNeedsNoApiKey() {
        assertTrue(ModelProviders.LOCAL_DEPLOYMENT.contains(ModelProviders.OLLAMA));

        assertTrue(!ModelProviders.requiresApiKey(ModelProviders.OLLAMA),
            "Ollama 跑在自己机房里，没有 API Key；要求它提供凭据等于把本地部署挡在模型治理之外");
        assertTrue(ModelProviders.requiresApiKey(ModelProviders.DASHSCOPE));
        assertTrue(ModelProviders.requiresApiKey(ModelProviders.GLM));
        assertTrue(ModelProviders.requiresApiKey(null), "厂商未知时按需要凭据处理，方向上更安全");
    }

    @Test
    @DisplayName("未知厂商仍然 fast fail，不静默降级")
    void unknownProviderStillFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> ModelProvider.of("not-a-vendor"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> ModelProvider.of(""));
    }

    /** 定位 starter 的工厂源码（本测试在 admin 模块，需跨模块读文件）。 */
    private Path factorySource() {
        Path relative = Paths.get("../customer-work-starter/src/main/java/com/richard/fyoung/"
            + "customerwork/infra/config/ChatModelFactory.java");
        assertTrue(Files.exists(relative),
            "找不到 ChatModelFactory 源码（工作目录应为 customer-admin-server 模块根）：" + relative);
        return relative;
    }
}
