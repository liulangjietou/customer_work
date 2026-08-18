package com.richard.fyoung.customeradmin.datascope;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据权限配置（{@code admin.data-scope.*}）。
 *
 * <p>默认开启，且与多租户开关相互独立：单租户部署同样可能需要"每个人只看自己的东西"，
 * 把它绑在 {@code admin.tenant.enabled} 上会让单租户用户无法单独使用这项能力。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.data-scope")
public class DataScopeProperties {

    /** 是否开启用户维度（仅本人）行级过滤。 */
    private boolean enabled = true;
}
