package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.safety.security.HttpTargetGuard;
import com.richard.fyoung.customerwork.safety.security.HttpTargetPolicy;
import com.richard.fyoung.customerwork.safety.security.InternalAddressPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HTTP 请求工具（{@code httpclient}）与开发者工具箱 HTTP 调试的 SSRF 收口防御点。
 *
 * <p><b>算法不在本类</b>：判定逻辑是 starter 的 {@link HttpTargetGuard}（与 RAG 知识库地址校验同一份实现，
 * 差异只在 {@link InternalAddressPolicy} 枚举的选取）。本类只做两件事：把 {@link AdminSystemToolProperties}
 * 的白名单绑成 starter 的策略 POJO，把 {@link HttpTargetForbiddenException} 转成 {@link BizException}
 * 交给全局异常处理器。</p>
 *
 * <p>本工具的目标地址由<b>大模型</b>决定，故策略取 {@link InternalAddressPolicy#DENY_INTERNAL}：
 * <ul>
 *   <li>默认（白名单为空）：DNS 解析后按 IP 判定，命中环回/内网/链路本地即拒绝
 *       （{@code internal.example.com} 这类"域名指向内网"的绕过也会被挡住），公网放行；</li>
 *   <li>白名单非空（收紧模式）：host 字符串匹配白名单，未命中直接拒绝；命中后仍解析并按 IP 判定，
 *       放行内网/环回（显式列入白名单即信任该 host，本地联调依赖它调 127.0.0.1），
 *       但链路本地/云元数据、未指定与组播地址永久拒绝。</li>
 * </ul></p>
 *
 * <p>"唯一防御点"成立的前提：调用方已禁用自动重定向跟随，3xx 作为终态响应返回。若重新打开自动跟随，
 * "合规公网域名 302 指向内网地址"即可绕过本校验——改动前务必同步评估这里。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SystemToolHttpGuard {

    private final HttpTargetGuard targetGuard;

    // 类上存在两个构造器，Spring 无法自动判定，必须显式标注注入用哪一个；
    // 另一个（带 AddressResolver）仅供单测注入确定性解析器。
    @Autowired
    public SystemToolHttpGuard(AdminSystemToolProperties properties) {
        this(properties, java.net.InetAddress::getAllByName);
    }

    /** 可替换 DNS 解析器仅用于确定性测试；生产使用系统 DNS。 */
    SystemToolHttpGuard(AdminSystemToolProperties properties, HttpTargetGuard.AddressResolver addressResolver) {
        // 白名单每次校验现取（配置对象可在运行期刷新），故以 Supplier 形式注入策略。
        // 默认模式拒内网（地址由大模型决定）；白名单命中后放宽到 ALLOW_INTERNAL：
        // 显式列入白名单即信任该 host（与远程 MCP/RAG 链路一致），本地联调才能调 127.0.0.1，
        // 链路本地/元数据等永远可疑地址仍由策略永久拒绝。
        this.targetGuard = new HttpTargetGuard(() -> HttpTargetPolicy.ofResolvedAllowlist(
            properties.getHttp().getAllowedHosts(),
            InternalAddressPolicy.DENY_INTERNAL,
            InternalAddressPolicy.ALLOW_INTERNAL), addressResolver);
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
