package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * 头像静态访问：把 {@code {urlPrefix}{filename}} 映射到落盘目录的文件资源（WebFlux RouterFunction）。
 *
 * <p>单段路径变量 {@code {filename}} 天然不匹配多级路径（{@code /} 不会落到本处理器），再显式拒绝
 * {@code ..} / {@code \\} 并用规范化路径校验落在落盘目录之内（双重防路径穿越）。文件不存在返回 404。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AvatarResourceConfig {

    private static final String PARENT_DIR_TOKEN = "..";

    @Bean
    public RouterFunction<ServerResponse> avatarResourceRouter(CustomerWorkProperties properties) {
        CustomerWorkProperties.UserAuth.Avatar avatar = properties.getUserAuth().getAvatar();
        Path baseDir = Paths.get(avatar.getDirectory()).toAbsolutePath().normalize();
        String pattern = avatar.getUrlPrefix() + "{filename}";
        return RouterFunctions.route(GET(pattern),
            request -> serve(baseDir, request.pathVariable("filename")));
    }

    private Mono<ServerResponse> serve(Path baseDir, String filename) {
        if (filename.contains("/") || filename.contains("\\") || filename.contains(PARENT_DIR_TOKEN)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的头像文件名"));
        }
        Path target = baseDir.resolve(filename).normalize();
        if (!target.startsWith(baseDir)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的头像路径"));
        }
        if (!Files.isRegularFile(target)) {
            return ServerResponse.notFound().build();
        }
        Resource resource = new FileSystemResource(target);
        MediaType mediaType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ServerResponse.ok().contentType(mediaType).bodyValue(resource);
    }
}
