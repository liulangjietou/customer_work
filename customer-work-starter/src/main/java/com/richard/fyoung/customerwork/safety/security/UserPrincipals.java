package com.richard.fyoung.customerwork.safety.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * 从 exchange 取出已鉴权用户身份——{@link UserAuthWebFilter} 的读取侧。
 *
 * <p>过滤器把 {@link UserPrincipal} 放进 {@link UserAuthWebFilter#PRINCIPAL_ATTR}，
 * 每个受保护端点再取出来。取的这三行此前在 6 个 Controller 里各写一遍，
 * 而且写出了两种变量名（{@code user} 与 {@code principal}）——复制粘贴的痕迹。</p>
 *
 * <p>放在过滤器旁边而不是某个 Controller 模块里：写入方与读取方是同一件事的两半，
 * 属性键 {@code PRINCIPAL_ATTR} 换了名字时，两边应该一起改、一起编译报错。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class UserPrincipals {

    private UserPrincipals() {
    }

    /**
     * 取当前请求的用户身份；未鉴权直接抛 401。
     *
     * <p>fast-fail：能走到受保护端点却没有身份，说明过滤器链装配漏了，
     * 这种情况要立刻炸出来，而不是让下游拿着 null 继续跑。</p>
     */
    public static UserPrincipal require(ServerWebExchange exchange) {
        UserPrincipal principal = exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR);
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return principal;
    }
}
