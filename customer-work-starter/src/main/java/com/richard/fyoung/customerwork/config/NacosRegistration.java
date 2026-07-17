package com.richard.fyoung.customerwork.config;

import lombok.Builder;
import lombok.Getter;

/**
 * Nacos 服务注册规格：一个不可变的注册参数载体（连接信息 + 实例信息）。
 *
 * <p>作为 {@link NacosServiceRegistrar} 的构造入参，把「连哪个 Nacos」与「注册成什么实例」两组参数聚合，
 * 使注册器成为可跨模块复用的普通类——starter（app-server）与 admin-server 各自按自身配置构造本对象即可，
 * 无需各写一套注册逻辑。字段全部 final，构造后不可变。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
public class NacosRegistration {

    // ===== Nacos 连接（与 NacosPromptService.buildProperties 同源）=====
    /** Nacos 服务地址，如 {@code localhost:8848}。 */
    private final String serverAddr;
    /** 命名空间 ID；留空走 public。须与网关发现端一致（本项目约定 customer-work）。 */
    private final String namespace;
    /** 分组，默认 {@code DEFAULT_GROUP}。 */
    private final String group;
    /** Nacos 用户名；留空表示不鉴权。 */
    private final String username;
    /** Nacos 密码。 */
    private final String password;

    // ===== 注册实例 =====
    /** 服务名（= 应用名），网关按此名 lb:// 路由。 */
    private final String serviceName;
    /** 对外暴露 IP；留空由注册器自动探测本机地址。 */
    private final String ip;
    /** 服务端口。 */
    private final int port;
    /** 协议（http / https），写入实例 metadata 供网关按需使用。 */
    private final String scheme;
    /** 上下文路径（无则空串），写入实例 metadata 供网关按需使用。 */
    private final String contextPath;
}
