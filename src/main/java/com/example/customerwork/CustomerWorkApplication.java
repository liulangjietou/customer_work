package com.example.customerwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 智能客服系统启动类。
 *
 * <p>本项目对应业务流程图中"框架原生支持"的核心 Agent 链路（②会话与上下文 / ③意图路由 /
 * ④子 Agent 执行 / ⑤观察-回复循环），可直接运行。</p>
 *
 * <p>①接入治理（Higress/RocketMQ）与 ⑥数据飞轮（RM Gallery/Trinity-RFT）属于生产工程化范畴，
 * 需要结合各自基础设施落地，本项目以注释与扩展点（Hook、接口）的形式预留位置，不内置实现。</p>
 */
// 会话用 DataSource 仅在 mode=mysql 时由 SessionConfig 手动构建，
// 因此排除 Spring Boot 的 DataSource 自动配置（避免要求 spring.datasource.url）。
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class CustomerWorkApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerWorkApplication.class, args);
    }
}
