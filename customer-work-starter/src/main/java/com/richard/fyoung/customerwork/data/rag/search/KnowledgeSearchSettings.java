package com.richard.fyoung.customerwork.data.rag.search;

import lombok.Data;

/**
 * 外部 RAG 检索客户端的运行参数（纯 POJO，不带 {@code @ConfigurationProperties}）。
 *
 * <p>starter 只提供 {@link KnowledgeSearchOps} 实现，配置前缀由宿主模块自己定义
 * （admin 用 {@code admin.rag.*}），把自己的 Properties 映射成本类再 new 客户端，
 * 这样 starter 不绑定任何一个宿主的配置前缀。字段默认值与 admin 侧保持一致。</p>
 *
 * <p>地址白名单不在本类：SSRF 判定由宿主注入的 {@code HttpTargetGuard} 承载
 * （策略与白名单都在那一侧绑定），本类只管超时。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class KnowledgeSearchSettings {

    /** TCP 连接超时（秒）。 */
    private int connectTimeoutSeconds = 5;

    /** 单次检索请求的请求级超时（秒），检索与连通性探测共用。 */
    private int requestTimeoutSeconds = 8;

    /** 多库并发检索的整体等待上限（秒）：超时即按空召回降级，绝不拖死对话。 */
    private int retrievalTimeoutSeconds = 10;

    /**
     * 请求超时配置项的<b>外部名称</b>，只用于超时诊断文案（"可调大 xxx 后重试"）。
     * 宿主模块填自己的配置键（admin 填 {@code admin.rag.request-timeout-seconds}），
     * 让排查的人拿到提示就能直接去改对应配置，不必反查是哪个前缀。
     */
    private String requestTimeoutConfigKey = "rag.request-timeout-seconds";
}
