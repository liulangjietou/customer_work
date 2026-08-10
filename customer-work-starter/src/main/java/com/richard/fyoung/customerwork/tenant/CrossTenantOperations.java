package com.richard.fyoung.customerwork.tenant;

import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;

import java.util.function.Supplier;

/**
 * 有意的跨租户操作：在给定代码块内跳过 {@code tenant_id} 行级过滤。
 *
 * <p>合法用途只有两类，且都必须在调用点写明理由：</p>
 * <ul>
 *   <li><b>登录</b>——按用户名查 {@code sys_user} 时还不知道用户属于哪个租户，这是先有鸡还是先有蛋的必然；</li>
 *   <li><b>运营方全局视角</b>——租户列表、跨租户统计，调用入口须先过运营方权限校验。</li>
 * </ul>
 *
 * <p>刻意做成显式方法而非"给 TenantContext 塞一个代表全局的特殊值"：后者会让
 * "忘记设置上下文"和"故意查全局"在代码上长得一模一样，一处疏漏就是全量数据泄露。
 * 显式包裹则是可 grep、可 review 的白名单——搜这个类的调用点就能穷举所有越权口子。</p>
 * @author owlzhangfq@gmail.com
 */
public final class CrossTenantOperations {

    private static final IgnoreStrategy TENANT_LINE_OFF = IgnoreStrategy.builder().tenantLine(true).build();

    /**
     * 嵌套深度：MyBatis-Plus 的 {@code clearIgnoreStrategy()} 是无条件 remove，
     * 内层作用域结束会把外层的豁免一并清掉，导致外层剩余语句意外恢复过滤。
     * 自己数一层深度，只在最外层进入/退出时开关，嵌套调用才是安全的。
     */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private CrossTenantOperations() {
    }

    /** 在跳过租户过滤的作用域内执行查询并返回结果。 */
    public static <T> T execute(Supplier<T> action) {
        enter();
        try {
            return action.get();
        } finally {
            exit();
        }
    }

    /** {@link #execute(Supplier)} 的无返回值版本。 */
    public static void run(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    private static void enter() {
        int depth = DEPTH.get();
        if (depth == 0) {
            InterceptorIgnoreHelper.handle(TENANT_LINE_OFF);
        }
        DEPTH.set(depth + 1);
    }

    private static void exit() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            InterceptorIgnoreHelper.clearIgnoreStrategy();
            DEPTH.remove();
            return;
        }
        DEPTH.set(depth);
    }
}
