package com.richard.fyoung.customeradmin.ticket.config;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 当前登录坐席身份解析：取 Sa-Token 会话里的登录名（username）作为坐席标识（agentId/assignee）。
 *
 * <p>登录名而非数字 id 作为坐席标识，可读性更好（8080 侧的 assignee/actorId、WS 凭证里的 agentId
 * 都用它）。登录名在登录时写入 TokenSession（见 {@code AuthService#login}）。所有工单接口都在
 * {@code @SaCheckPermission} 之后，正常已登录；取不到时 fast-fail 抛未登录。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class CurrentAgentResolver {

    private static final String SESSION_USERNAME_KEY = "username";

    /** 返回当前登录坐席的登录名；无登录态时 fast-fail。 */
    public String currentAgentId() {
        String username = StpUtil.getTokenSession().getString(SESSION_USERNAME_KEY);
        if (!StringUtils.hasText(username)) {
            throw new BizException(ResultCode.UNAUTHORIZED, "无法获取当前坐席身份");
        }
        return username;
    }
}
