package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.memory.MemoryConsent;
import com.richard.fyoung.customerwork.core.memory.MemoryConsentService;
import com.richard.fyoung.customerwork.core.memory.MemoryConsentStatus;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectKey;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/** 终端用户长期记忆同意、查看与删除接口；主体一律来自已验签 JWT。 */
@RestController
@RequestMapping("/api/customer/user/privacy")
@Tag(name = "用户隐私", description = "长期记忆同意、查看与删除")
public class UserPrivacyController {

    private final MemorySubjectResolver subjectResolver;
    private final MemoryConsentService consentService;

    public UserPrivacyController(MemorySubjectResolver subjectResolver,
                                 MemoryConsentService consentService) {
        this.subjectResolver = subjectResolver;
        this.consentService = consentService;
    }

    public record MemoryConsentRequest(Boolean granted, String consentVersion) {
    }

    public record MemoryConsentResponse(boolean granted,
                                        String consentVersion,
                                        Long grantedAtMs,
                                        Long withdrawnAtMs,
                                        long updatedAtMs) {
    }

    public record MemoryListResponse(List<String> memories, List<String> facts, int count) {
    }

    public record MemoryExportResponse(String schemaVersion,
                                       String subjectType,
                                       String agentId,
                                       MemoryConsentResponse consent,
                                       List<String> memories,
                                       List<String> facts,
                                       long exportedAtMs) {
    }

    @Operation(summary = "查询长期记忆同意状态")
    @GetMapping("/memory-consent")
    public Mono<MemoryConsentResponse> consent(ServerWebExchange exchange) {
        return blocking(() -> response(consentService.status(subject(exchange))));
    }

    @Operation(summary = "授权或撤回长期记忆")
    @PutMapping("/memory-consent")
    public Mono<MemoryConsentResponse> updateConsent(ServerWebExchange exchange,
                                                       @RequestBody MemoryConsentRequest request) {
        if (request == null || request.granted() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "granted is required");
        }
        return blocking(() -> {
            MemorySubjectKey subject = subject(exchange);
            MemoryConsent consent = request.granted()
                ? consentService.grant(subject, request.consentVersion())
                : consentService.withdraw(subject);
            return response(consent);
        });
    }

    @Operation(summary = "查看本人长期记忆")
    @GetMapping("/memory")
    public Mono<MemoryListResponse> memories(ServerWebExchange exchange,
                                              @RequestParam(defaultValue = "50") int limit) {
        return blocking(() -> {
            MemorySubjectKey subject = subject(exchange);
            List<String> memories = consentService.list(subject, limit);
            List<String> facts = consentService.listFacts(subject, limit);
            return new MemoryListResponse(memories, facts, memories.size() + facts.size());
        });
    }

    @Operation(summary = "导出本人长期记忆", description = "下载 JSON；主体来自已验签 JWT")
    @GetMapping(value = "/memory/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<MemoryExportResponse>> exportMemory(ServerWebExchange exchange,
                                                                    @RequestParam(defaultValue = "200") int limit) {
        return blocking(() -> {
            MemorySubjectKey subject = subject(exchange);
            MemoryConsent consent = consentService.status(subject);
            List<String> memories = consentService.list(subject, limit);
            List<String> facts = consentService.listFacts(subject, limit);
            MemoryExportResponse body = new MemoryExportResponse(
                "memory-export-v1", subject.subjectType().name(), subject.agentId(),
                response(consent), memories, facts, System.currentTimeMillis());
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=memory-export.json")
                .body(body);
        });
    }

    @Operation(summary = "删除本人长期记忆", description = "先撤回处理同意，再同步擦除长期记忆与事实")
    @DeleteMapping("/memory")
    public Mono<Void> deleteMemory(ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> consentService.withdraw(subject(exchange)))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    private MemorySubjectKey subject(ServerWebExchange exchange) {
        UserPrincipal user = exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return subjectResolver.user(user.tenantId(), user.userId());
    }

    private MemoryConsentResponse response(MemoryConsent consent) {
        return new MemoryConsentResponse(
            consent.status() == MemoryConsentStatus.GRANTED,
            consent.consentVersion(), consent.grantedAtMs(), consent.withdrawnAtMs(), consent.updatedAtMs());
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> task) {
        return Mono.fromCallable(task).subscribeOn(Schedulers.boundedElastic());
    }
}
