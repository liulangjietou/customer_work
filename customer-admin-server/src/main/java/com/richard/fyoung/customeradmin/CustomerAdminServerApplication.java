package com.richard.fyoung.customeradmin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * customer-admin-server 启动类（智能体客服后台管理系统 · Spring MVC）。
 *
 * <p>面向系统管理员与运营人员：用户权限（RBAC/Sa-Token）、AI 模型 / MCP / Skill / 智能体管理
 * （MyBatis-Plus 持久化），并支持对智能体在线聊天与 VibeCoding（复用 customer-work-starter
 * 的 Agent 构建能力）。与 WebFlux 对话 API（{@code customer-work-app-server}）、既有管理控制台
 * （{@code customer-channel}）按部署形态分离：本模块是独立的运营后台，走 servlet。</p>
 * @author owlzhangfq@gmail.com
 */
@SpringBootApplication
@MapperScan("com.richard.fyoung.customeradmin.**.mapper")
@EnableAsync
public class CustomerAdminServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerAdminServerApplication.class, args);
    }
}
