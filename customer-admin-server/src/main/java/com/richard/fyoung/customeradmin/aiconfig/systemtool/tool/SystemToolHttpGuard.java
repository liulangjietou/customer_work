package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import com.richard.fyoung.customerwork.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.security.HttpTargetGuard;
import com.richard.fyoung.customerwork.security.HttpTargetPolicy;
import com.richard.fyoung.customerwork.security.InternalAddressPolicy;
import org.springframework.stereotype.Component;

/**
 * HTTP 请求工具（{@code httpclient}）与开发者工具箱 HTTP 调试的 SSRF 收口防御点。
 *
 * <p><b>算法不在本类</b>：判定逻辑是 starter 的 {@link HttpTargetGuard}（与 RAG 知识库地址校验同一份实现，
 * 差异只在 {@link InternalAddressPolicy} 一个枚举）。本类只做两件事：把 {@link AdminSystemToolProperties}
 * 的白名单绑成 starter 的策略 POJO，把 {@link HttpTargetForbiddenException} 转成 {@link BizException}
 * 交给全局异常处理器。</p>
 *
 * <p>本工具的目标地址由<b>大模型</b>决定，故策略取 {@link InternalAddressPolicy#DENY_INTERNAL}：
 * <ul>
 *   <li>默认（白名单为空）：DNS 解析后按 IP 判定，命中环回/内网/链路本地即拒绝
 *       （{@code internal.example.com} 这类"域名指向内网"的绕过也会被挡住），公网放行；</li>
 *   <li>白名单非空（收紧模式）：只按 host 字符串匹配白名单，命中放行、否则拒绝。</li>
 * </ul></p>
 *
 * <p>"唯一防御点"成立的前提：调用方已禁用自动重定向跟随，3xx 作为终态响应返回。若重新打开自动跟随，
 * "合规公网域名 302 指向内网地址"即可绕过本校验——改动前务必同步评估这里。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SystemToolHttpGuard {

    private final HttpTargetGuard targetGuard;

    public SystemToolHttpGuard(AdminSystemToolProperties properties) {
        // 白名单每次校验现取（配置对象可在运行期刷新），故以 Supplier 形式注入策略
        this.targetGuard = new HttpTargetGuard(() -> HttpTargetPolicy.of(
            properties.getHttp().getAllowedHosts(), InternalAddressPolicy.DENY_INTERNAL));
    }

    /**
     * 校验目标 URL 是否允许访问；不允许则 fast fail 抛出 {@link BizException}。
     * @param url 工具入参里的目标地址
     */
    public void checkAllowed(String url) {
        try {
            targetGuard.checkAllowed(url);
        } catch (HttpTargetForbiddenException e) {
            throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, e.getMessage());
        }
    }

    /**
     * 暴露底层 starter Guard：供 HTTP 执行核心（starter 侧的 {@code *DevToolOps}）在内部收口 SSRF 校验，
     * 由各自的调用壳把 {@link HttpTargetForbiddenException} 转成业务异常。
     */
    public HttpTargetGuard targetGuard() {
        return targetGuard;
    }
}
