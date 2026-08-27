package com.richard.fyoung.customeradmin.publicdeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 对外开放实例上的内部运维工具下架闸门。
 *
 * <p>拦的是三个前缀：{@code /api/sql/**}（SQL 客户端与数据源配置）、
 * {@code /api/workbench/**}（内网账号本、个人令牌、脚本回调）、{@code /api/devtools/**}（开发者工具箱）。
 * 它们能在已配数据源上执行任意语句、读出目标站点密码、以服务端身份发起 HTTP 请求，
 * 破坏面与调用者是谁无关，因此对外实例上一律拒绝，超管也不例外。</p>
 *
 * <p><b>为什么用拦截器而不是给 Controller 加 {@code @ConditionalOnProperty}</b>：
 * 装配条件散在十个 Controller 上，新增一个内部工具很容易忘记加；集中在这里，
 * 前缀清单与权限族清单同源，改一处即全覆盖。</p>
 *
 * <p><b>为什么必须排在 Sa-Token 之前</b>：{@code /api/workbench/agent/**} 是免登接口
 * （ScriptCat 脚本回调走 X-Workbench-Token），排在登录校验之后就永远轮不到它。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class InternalToolGuardInterceptor implements HandlerInterceptor {

    /**
     * 内部工具的 API 命名空间，与 {@code ControlPlanePermissions} 的内部工具族一一对应。
     *
     * <p>不带尾斜杠：命名空间本身（{@code /api/devtools}）与它下面的所有路径都要拦，
     * 匹配按路径段落地，见 {@link #blocked(String)}。</p>
     */
    static final List<String> BLOCKED_NAMESPACES = List.of(
        "/api/sql",
        "/api/workbench",
        "/api/devtools");

    private final ObjectMapper objectMapper;

    public InternalToolGuardInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 命中判定按路径段边界，不是裸的 {@code startsWith}。
     *
     * <p>否则 {@code /api/sqlx/...} 这类相邻命名空间会被一起拦掉——今天没有这样的路径，
     * 但一个按字符前缀匹配的安全闸门迟早会误伤某个新接口，而误伤表现为 403，
     * 排查时很难第一眼联想到这里。</p>
     */
    private boolean blocked(String uri) {
        return BLOCKED_NAMESPACES.stream()
            .anyMatch(ns -> uri.equals(ns) || uri.startsWith(ns + "/"));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();
        if (!blocked(uri)) {
            return true;
        }
        log.info("internal tool endpoint rejected on public deployment, uri={}", uri);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.failure(ResultCode.FEATURE_NOT_AVAILABLE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}
