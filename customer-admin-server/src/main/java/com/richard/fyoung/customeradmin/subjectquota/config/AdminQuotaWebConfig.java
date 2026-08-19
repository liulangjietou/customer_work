package com.richard.fyoung.customeradmin.subjectquota.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.subjectquota.runtime.AdminQuotaInterceptor;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册后台用量判定拦截器。
 *
 * <p>路径按 {@code admin.subject-quota.path-patterns} 配（默认只覆盖真正调模型的三条入口）：
 * 后台的列表、详情、附件下载不消耗额度，把它们算进去只会让内部员工翻两页记录就被限。</p>
 *
 * <p>排在租户拦截器之前（{@code LOWEST_PRECEDENCE - 10}）：判定要拿登录态与租户，
 * 但不依赖租户 ThreadLocal——拦截器内部自己按登录态包了一层租户上下文。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AdminQuotaWebConfig implements WebMvcConfigurer {

    private final SubjectQuotaGuard guard;
    private final ObjectMapper objectMapper;
    private final AdminSubjectQuotaProperties properties;

    public AdminQuotaWebConfig(SubjectQuotaGuard adminSubjectQuotaGuard,
                               ObjectMapper objectMapper,
                               AdminSubjectQuotaProperties properties) {
        this.guard = adminSubjectQuotaGuard;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!properties.isEnabled() || properties.getPathPatterns() == null
            || properties.getPathPatterns().isEmpty()) {
            return;
        }
        registry.addInterceptor(new AdminQuotaInterceptor(guard, objectMapper))
            .addPathPatterns(properties.getPathPatterns())
            .order(Ordered.LOWEST_PRECEDENCE - 10);
    }
}
