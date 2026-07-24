package com.richard.fyoung.customerchannel.access.support;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 渠道接入层统一的 {@link WebClient} 构建入口。
 *
 * <p>强制使用 JDK 默认地址解析器（{@link DefaultAddressResolverGroup}）而非 Netty 自带的 DNS 解析器：
 * Apple Silicon（osx-aarch_64）上 Netty 的 macOS 原生解析库缺失时，Netty DNS 解析器会向 DNS 服务器
 * 查询 {@code localhost} 这类仅存在于本机 hosts 的域名并失败（Failed to resolve 'localhost'）。
 * JDK 解析器走系统 hosts，行为与操作系统一致。</p>
 * @author owlzhangfq@gmail.com
 */
public final class WebClients {

    private WebClients() {
    }

    /** 返回已配置 JDK 地址解析器的 WebClient.Builder，接入层所有 WebClient 一律从这里出。 */
    public static WebClient.Builder builder() {
        HttpClient httpClient = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
