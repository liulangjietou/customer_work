package com.acme.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 下游接入示例：一个与本项目**完全不同包名**（com.acme.support）的第三方应用。
 *
 * <p>它只在 pom 里依赖 {@code customer-work-spring-boot-starter}，没有任何针对本库的
 * {@code @ComponentScan}/{@code @Import}——全部能力由 starter 的 {@code @AutoConfiguration} 自动装配。
 * 这正是"引入即用、零扫描"的证明。</p>
 *
 * <p>运行：{@code export DASHSCOPE_API_KEY=sk-xxx && java -jar downstream-sample.jar}，
 * 然后 {@code POST /support/ask {"message":"..."}}。</p>
 * @author owlzhangfq@gmail.com
 */
@SpringBootApplication
public class SupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportApplication.class, args);
    }
}
