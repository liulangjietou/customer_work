package com.richard.fyoung.customerwork.core.model;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 模型响应的文本提取——框架 {@link ChatResponse} 到纯文本的<b>唯一</b>适配点。
 *
 * <p><b>为什么要有这个类</b>：把响应里的 {@link TextBlock} 抠出来拼成字符串这段六行流水，
 * 此前在 7 个地方逐字重复——starter 的会话总结 / 工单分类 / 评测裁判 / 视觉 OCR，
 * admin 的代码知识库 / 协作编程 / Git 助手。它不是业务逻辑，是<b>框架 API 的适配层</b>：
 * AgentScope 升级只要动了 {@code getContent()} 的形状或 {@code TextBlock} 的位置，
 * 就要改 7 处。跨模块共享的东西定义在双方的公共依赖 starter 上，
 * 不靠"两边各写一份、口头保持一致"。</p>
 *
 * <p><b>刻意只做提取，不管异常</b>：7 个调用方对"拿不到文本"的处置各不相同——
 * starter 抛 {@link IllegalStateException}（不感知任何业务错误码体系），
 * admin 抛携带 {@code ResultCode} 的业务异常，且各自的错误码不同。
 * 把异常也收进来就得让 starter 认识 admin 的错误码，那是反向依赖。
 * 同理也<b>不</b>合并调用方"列表为空"与"有响应但无文本"这两个判断：
 * 前者是模型调用什么都没返回，后者是返回了但只有工具调用/图片，
 * 排查时是两条不同的线索，合并等于把诊断信息丢掉。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ModelResponses {

    private ModelResponses() {
    }

    /**
     * 按顺序拼接响应里全部非空文本块；{@code null} / 空列表返回空串。
     *
     * <p>返回空串代表"模型没给出任何文本"，调用方自行决定这算不算错误、抛什么。</p>
     */
    public static String text(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        return responses.stream()
            .flatMap(response -> response.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining());
    }

    /**
     * 从模型输出里截出最外层 JSON 对象；截不出返回 {@code null}。
     *
     * <p>模型即便被要求"只输出 JSON"，也常带上 ```json 围栏或一句解释，
     * 所以取首个 <code>{</code> 到末个 <code>}</code> 之间的整段，而不是直接把整段文本喂给解析器。
     * 这段逻辑此前在会话总结 / 工单分类 / Git 助手三处逐字重复——
     * 解析口径一旦漂移（本项目踩过 Jackson {@code readTree} 尾部 token 的坑），
     * 修好一处另外两处照旧。</p>
     */
    public static String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : null;
    }
}
