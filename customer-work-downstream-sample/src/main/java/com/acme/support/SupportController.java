package com.acme.support;

import com.richard.fyoung.customerwork.service.CustomerServiceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 下游自定义接口：直接复用 starter 自动装配进来的 {@link CustomerServiceService}。
 *
 * <p>说明：starter 只提供能力 Bean，不强加 HTTP 接口——接口形态由你的应用自定义。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/support")
public class SupportController {

    private final CustomerServiceService service;

    public SupportController(CustomerServiceService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    public Mono<String> ask(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "acme-anonymous");
        return service.chat(sessionId, body.getOrDefault("message", ""));
    }
}
