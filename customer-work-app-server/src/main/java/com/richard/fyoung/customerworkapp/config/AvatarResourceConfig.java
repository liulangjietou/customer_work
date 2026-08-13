package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerworkapp.service.AvatarStorageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import com.richard.fyoung.customerwork.infra.config.properties.UserAuthProperties;

/**
 * 头像访问：把 {@code {urlPrefix}{key}} 映射到对象存储里的头像对象（WebFlux RouterFunction）。
 *
 * <p>此前直接映射本地落盘目录的文件资源，多副本部署时 A 机上传的头像 B 机读不到。改从
 * {@link AvatarStorageService#read} 取字节后，URL 契约不变（{@code cw_user.avatar_url} 里的存量
 * 地址继续有效——读不到对象会回落旧目录）。</p>
 *
 * <p>key 形如 {@code 202608/{uuid}.png}（含斜杠），故用 {@code {*key}} 捕获剩余全部路径段；
 * 路径穿越校验在 {@link AvatarStorageService} 的旧目录兜底里做（对象存储侧 key 不参与文件系统解析）。
 * 读不到即 404，不泄漏存储细节。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AvatarResourceConfig {

    @Bean
    public RouterFunction<ServerResponse> avatarResourceRouter(CustomerWorkProperties properties,
                                                               AvatarStorageService avatarStorageService) {
        UserAuthProperties.Avatar avatar = properties.getUserAuth().getAvatar();
        // {*key} 捕获剩余全部路径段（含斜杠）；单段的 {key} 匹配不到带 yyyyMM 前缀的新 key
        String pattern = avatar.getUrlPrefix() + "{*key}";
        return RouterFunctions.route(GET(pattern),
            request -> serve(avatarStorageService, request.pathVariable("key")));
    }

    private Mono<ServerResponse> serve(AvatarStorageService storageService, String rawKey) {
        // {*key} 捕获的值以 / 开头，剥掉后才是存储 key
        String key = rawKey.startsWith("/") ? rawKey.substring(1) : rawKey;
        if (key.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的头像路径"));
        }
        // 读取是阻塞 IO（对象存储 HTTP / 本地盘读），不能占着事件循环线程
        return Mono.fromCallable(() -> storageService.read(key))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(bytes -> ServerResponse.ok()
                .contentType(mediaTypeOf(key))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .bodyValue(new ByteArrayResource(bytes)))
            .onErrorResume(IOException.class, e -> ServerResponse.notFound().build());
    }

    /** 按 key 的文件名判 Content-Type；判不出按二进制流（浏览器下载而非渲染，但不会出错）。 */
    private MediaType mediaTypeOf(String key) {
        return MediaTypeFactory.getMediaType(key).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
