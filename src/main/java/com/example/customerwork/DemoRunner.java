package com.example.customerwork;

import com.example.customerwork.service.CustomerServiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 命令行体验 Demo（对应整条核心链路 ②③④⑤ 的端到端演示）。
 *
 * <p>用 {@code --demo} profile 启动即可在控制台跑三段典型对话：咨询、订单查询、退款（含人工确认）。
 * 它走的就是真实的 ReActAgent 链路，便于在不起前端的情况下验证框架能力。</p>
 *
 * <p>启动方式：{@code mvn spring-boot:run -Dspring-boot.run.profiles=demo}</p>
 *
 * <p>注意：main/demo 中使用 .block() 仅用于演示输出，生产 Agent 逻辑中严禁 .block()。</p>
 */
@Component
@Profile("demo")
public class DemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final CustomerServiceService service;

    public DemoRunner(CustomerServiceService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        String session = "demo-session-001";
        log.info("==== 智能客服 Demo 开始（会话 {}）====", session);

        ask(session, "你们支持七天无理由退货吗？");
        ask(session, "帮我查一下订单 20260613001 的状态和物流");
        ask(session, "这个订单 20260613001 我想退款，金额 299 元，不想要了");

        service.endSession(session);
        log.info("==== Demo 结束 ====");
    }

    private void ask(String session, String text) {
        log.info(">>> 用户: {}", text);
        // ⚠️ .block() 仅允许出现在此类演示场景；生产业务逻辑请用响应式链路
        String reply = service.chat(session, text).block();
        log.info("<<< 客服: {}\n", reply);
    }
}
