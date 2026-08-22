package com.richard.fyoung.customerwork.infra.config;

/**
 * 运行时配置切换前的缓存失效边界。
 *
 * <p>实现必须仅清理 {@code TenantContext} 当前租户的旧缓存，并在清理失败时
 * 向上抛异常。调用方据此拒绝切换新配置，避免新提示词或模型继续复用旧答案。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public interface RuntimeConfigCacheInvalidator {

    /** 严格失效当前租户的配置相关缓存；失败必须抛异常。 */
    void invalidateCurrentTenant();

    /**
     * 进入配置代际切换：先阻断新缓存读写，再清理当前租户旧缓存。
     * 自定义实现没有代际能力时沿用严格清理，仍保持正确性但不能复用跨实例缓存。
     */
    default void beginTransition(String nextContentHash) {
        invalidateCurrentTenant();
    }

    /** 配置应用成功后提交新代际。 */
    default void commitTransition(String nextContentHash) {
    }

    /** 配置应用失败后恢复旧代际。 */
    default void rollbackTransition(String nextContentHash) {
    }
}
