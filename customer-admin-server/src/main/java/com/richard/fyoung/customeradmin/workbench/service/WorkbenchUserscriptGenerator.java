package com.richard.fyoung.customeradmin.workbench.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成内嵌个人令牌的 ScriptCat/Tampermonkey 通用登录脚本。
 *
 * <p>读取 classpath 模板 {@code workbench/userscript-template.js}，替换四处占位符：
 * 令牌明文、API 基址、@connect 主机、@match 列表（每个启用站点 host 一行）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class WorkbenchUserscriptGenerator {

    private static final String TEMPLATE_PATH = "workbench/userscript-template.js";
    private static final String PH_TOKEN = "__TOKEN__";
    private static final String PH_API_BASE = "__API_BASE__";
    private static final String PH_CONNECT_HOST = "__CONNECT_HOST__";
    private static final String PH_MATCH_BLOCK = "__MATCH_BLOCK__";

    private final String publicApiBase;
    private final String template;

    public WorkbenchUserscriptGenerator(@Value("${admin.workbench.public-api-base}") String publicApiBase) {
        this.publicApiBase = stripTrailingSlash(publicApiBase);
        this.template = loadTemplate();
    }

    /**
     * @param rawToken 内嵌的令牌明文
     * @param hosts    启用站点 host 列表（用于 @match）
     */
    public String generate(String rawToken, List<String> hosts) {
        String connectHost = URI.create(publicApiBase).getHost();
        String matchBlock = buildMatchBlock(hosts);
        return template
            .replace(PH_MATCH_BLOCK, matchBlock)
            .replace(PH_CONNECT_HOST, connectHost != null ? connectHost : "localhost")
            .replace(PH_API_BASE, publicApiBase)
            .replace(PH_TOKEN, rawToken);
    }

    private String buildMatchBlock(List<String> hosts) {
        if (CollectionUtils.isEmpty(hosts)) {
            return "// @match        *://localhost/*   // 暂无启用站点，请先在工作台添加";
        }
        return hosts.stream()
            .map(host -> "// @match        *://" + host + "/*")
            .collect(Collectors.joining("\n"));
    }

    private String loadTemplate() {
        try {
            return StreamUtils.copyToString(new ClassPathResource(TEMPLATE_PATH).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("load workbench userscript template failed", e);
        }
    }

    private String stripTrailingSlash(String base) {
        return base != null && base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
