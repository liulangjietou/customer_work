package com.richard.fyoung.customerwork.infra.gateway;

import org.apache.ibatis.plugin.Interceptor;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 惰性跨库门面持有者：首次访问才建连、成功后缓存、失败不缓存。
 *
 * <p>三条语义缺一不可：<br>
 * - <b>惰性</b>：宿主启动期绝不触碰外库——后台不该因为客服端库没起来就启动不了；<br>
 * - <b>缓存</b>（双重检查）：连接池与 Mapper 环境建一次就够，不能每次请求重建；<br>
 * - <b>失败不缓存</b>：构建失败什么都不留，下次访问原样重试，覆盖"库稍后恢复"的场景。</p>
 *
 * <p>由 {@link CrossDbGateways#lazy} 创建。持有者（通常是宿主的一个 Bean）负责在销毁时调 {@link #close()}。</p>
 *
 * @param <T> 业务门面类型（由 assembler 从跨库环境装配而来）
 * @author owlzhangfq@gmail.com
 */
public final class CrossDbGatewayProvider<T> implements AutoCloseable {

    private final Supplier<CrossDbConnectionSettings> settingsSupplier;
    private final List<Class<?>> mapperClasses;
    private final List<String> mapperXmlLocations;
    private final Function<CrossDbGateway, T> assembler;
    private final Supplier<List<Interceptor>> pluginsSupplier;

    private volatile T cachedFacade;
    private volatile CrossDbGateway gateway;

    CrossDbGatewayProvider(Supplier<CrossDbConnectionSettings> settingsSupplier,
                           List<Class<?>> mapperClasses,
                           List<String> mapperXmlLocations,
                           Function<CrossDbGateway, T> assembler) {
        this(settingsSupplier, mapperClasses, mapperXmlLocations, List::of, assembler);
    }

    CrossDbGatewayProvider(Supplier<CrossDbConnectionSettings> settingsSupplier,
                           List<Class<?>> mapperClasses,
                           List<String> mapperXmlLocations,
                           Supplier<List<Interceptor>> pluginsSupplier,
                           Function<CrossDbGateway, T> assembler) {
        this.settingsSupplier = settingsSupplier;
        this.mapperClasses = mapperClasses;
        this.mapperXmlLocations = mapperXmlLocations;
        this.pluginsSupplier = pluginsSupplier;
        this.assembler = assembler;
    }

    /**
     * 取业务门面（惰性构建 + 探测 + 缓存）。
     *
     * @throws CrossDbUnavailableException 库不可达（调用方转成明确的业务提示）
     */
    public T get() {
        T facade = cachedFacade;
        if (facade != null) {
            return facade;
        }
        synchronized (this) {
            if (cachedFacade != null) {
                return cachedFacade;
            }
            CrossDbGateway built = CrossDbGateways.create(settingsSupplier.get(),
                mapperClasses, mapperXmlLocations, pluginsSupplier.get());
            T assembled;
            try {
                assembled = assembler.apply(built);
            } catch (Exception e) {
                // 业务门面装配失败同样要关掉刚建好的池，否则每次重试漏一个池
                built.close();
                throw e;
            }
            this.gateway = built;
            this.cachedFacade = assembled;
            return assembled;
        }
    }

    /** 是否已成功构建过（供宿主判断是否需要关池，避免为了关闭反而把库连起来）。 */
    public boolean isInitialized() {
        return cachedFacade != null;
    }

    @Override
    public void close() {
        CrossDbGateway current = gateway;
        if (current != null) {
            current.close();
        }
    }
}
