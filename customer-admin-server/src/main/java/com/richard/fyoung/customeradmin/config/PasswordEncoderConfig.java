package com.richard.fyoung.customeradmin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希：仅取 {@code spring-security-crypto} 的 {@link BCryptPasswordEncoder}，
 * 不引入完整 Spring Security 自动装配（会与 Sa-Token 鉴权流程冲突）。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
