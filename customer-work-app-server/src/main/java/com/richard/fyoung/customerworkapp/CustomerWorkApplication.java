package com.richard.fyoung.customerworkapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能客服应用启动类。
 *
 * <p>本应用位于独立包 {@code com.richard.fyoung.customerworkapp}，自身只装配本模块的 Controller；
 * 全部可复用能力（模型 / 记忆 / RAG / 工具 / 会话 / 安全 / 可观测 / Nacos 等）由
 * customer-work-starter 通过 {@code @AutoConfiguration} 自动装配——
 * 这正是"下游引入 starter 即得完整能力、无需关心基础包、无需手动 @ComponentScan"的体现。</p>
 *
 * <p>说明：starter 引入 MyBatis-Plus 后 spring-jdbc 进入类路径，故在 application.yml 里以
 * {@code spring.autoconfigure.exclude} 关闭 {@code DataSourceAutoConfiguration}——业务持久层由 starter 的
 * {@code CustomerWorkPersistenceConfig} 显式装配独立数据源（customerWorkDataSource），不用 Spring 自动数据源；
 * 定时任务与配置属性扫描已由 starter 的自动配置启用。</p>
 * @author owlzhangfq@gmail.com
 */
@SpringBootApplication
public class CustomerWorkApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerWorkApplication.class, args);
    }
}
