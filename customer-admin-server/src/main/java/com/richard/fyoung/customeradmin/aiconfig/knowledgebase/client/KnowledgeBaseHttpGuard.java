package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.safety.security.HttpTargetGuard;
import com.richard.fyoung.customerwork.safety.security.HttpTargetPolicy;
import com.richard.fyoung.customerwork.safety.security.InternalAddressPolicy;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库地址校验：本链路唯一的地址防御点，由 {@link KnowledgeSearchClient} 在发起请求前调用
 * （fast fail，命中即抛业务异常，不进入真实请求）。
 *
 * <p><b>算法不在本类</b>：判定逻辑是 starter 的 {@link HttpTargetGuard}（与系统工具的
 * {@code SystemToolHttpGuard} 同一份实现）。本类只做两件事：把 {@link AdminRagProperties} 的白名单绑成
 * starter 的策略 POJO，把 {@link HttpTargetForbiddenException} 转成 {@link BizException}。</p>
 *
 * <p><b>与 {@code SystemToolHttpGuard} 的信任边界差异（这也是两个 Bean 各留一份白名单的原因）</b>：
 * 系统工具的目标地址由<b>大模型</b>决定，默认必须拒内网；RAG 知识库的地址由持
 * {@code knowledge-base:add/edit} 权限的<b>管理员</b>在后台显式配置，且企业 RAG 服务基本都在内网
 * （用户本机即 {@code localhost:20002}），故策略取 {@link InternalAddressPolicy#ALLOW_INTERNAL}——
 * 放行内网/环回，只拦链路本地（云元数据服务 169.254.169.254 一类）。共用一份白名单会把 RAG 的内网地址
 * 一并授信给模型可控的 HTTP 工具，绝不可取。</p>
 *
 * <p>白名单非空即收紧模式：只按 host 字符串匹配，不再做 DNS 与地址判定。生产建议显式配置，
 * 把信任边界收敛到已知的几台 RAG 服务。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class KnowledgeBaseHttpGuard {

    private final HttpTargetGuard targetGuard;

    public KnowledgeBaseHttpGuard(AdminRagProperties properties) {
        // 白名单每次校验现取（配置对象可在运行期刷新），故以 Supplier 形式注入策略
        this.targetGuard = new HttpTargetGuard(() -> HttpTargetPolicy.of(
            properties.getAllowedHosts(), InternalAddressPolicy.ALLOW_INTERNAL));
    }

    /**
     * 校验目标 RAG 基址是否允许访问；不允许则 fast fail 抛出 {@link BizException}。
     * @param baseUrl 知识库配置里的服务基址
     */
    public void checkAllowed(String baseUrl) {
        try {
            targetGuard.checkAllowed(baseUrl);
        } catch (HttpTargetForbiddenException e) {
            throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, e.getMessage());
        }
    }

    /**
     * 暴露底层 starter Guard：供检索执行核心（starter 侧的 {@code KnowledgeSearchOps}）在发起请求前
     * 内部收口地址校验，由 {@link KnowledgeSearchClient} 把 {@link HttpTargetForbiddenException}
     * 转成业务异常（错误码与 {@link #checkAllowed} 一致）。
     */
    public HttpTargetGuard targetGuard() {
        return targetGuard;
    }
}
