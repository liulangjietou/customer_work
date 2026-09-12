package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.data.knowledge.KnowledgePublicSource;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeDocumentReference;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.dao.UserMessageSourceDao;
import com.richard.fyoung.customerworkapp.service.UserMessageSourceService;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真实 JWT/HTTP 路由；原文只能经应用 DAO 已授权的持久消息引用读取。 */
@WebFluxTest(UserMessageSourceController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class, UserMessageSourceService.class,
    ControllerSecurityTestConfiguration.UserAuth.class})
class UserMessageSourceControllerTest {
    private static final String SESSION = "uU1:conv-source";
    private static final String MESSAGE = "MSG-source";
    private static final String BASE = "/api/customer/user/sessions/" + SESSION + "/messages/" + MESSAGE + "/sources";
    @Autowired private WebTestClient client;
    @Autowired private UserJwtService jwt;
    @MockBean private UserMessageSourceDao messageSources;
    @MockBean private KnowledgeChunkMapper chunks;

    @BeforeEach
    void fixture() {
        var source = new KnowledgeRetrievalSource(1, "旧线索名称", "policy", "901", null,
            new KnowledgeDocumentReference(7L, 70L, 700L, 901L));
        savedSources(List.of(source));
        when(chunks.findPublicSource("default", "7", 70L, 700L, 901L, false))
            .thenReturn(new KnowledgePublicSource(901L, "历史标题", "授权知识库", 3, "source-v1", null));
        when(chunks.findPublicSource("default", "7", 70L, 700L, 901L, true))
            .thenReturn(new KnowledgePublicSource(901L, "历史标题", "授权知识库", 3, "source-v1", "历史原文 <script>plain</script>"));
    }

    @Test
    void listsOnlySavedReferencesWithCurrentAuthorizedMetadataAndNoBody() {
        client.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody().jsonPath("$[0].sourceIndex").isEqualTo(0)
            .jsonPath("$[0].status").isEqualTo("AVAILABLE")
            .jsonPath("$[0].title").isEqualTo("历史标题")
            .jsonPath("$[0].knowledgeBase").isEqualTo("授权知识库")
            .jsonPath("$[0].content").doesNotExist()
            .jsonPath("$[0].reference").doesNotExist();
    }

    @Test
    void previewsTheSavedHistoricalChunkWithPlainTextAndActualVersion() {
        client.get().uri(BASE + "/0").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody().jsonPath("$.status").isEqualTo("AVAILABLE")
            .jsonPath("$.content").isEqualTo("历史原文 <script>plain</script>")
            .jsonPath("$.versionNo").isEqualTo(3)
            .jsonPath("$.sourceVersion").isEqualTo("source-v1");
    }

    @Test
    void rejectsMissingOrInvalidJwtBeforeReadingAnyResource() {
        client.get().uri(BASE).exchange().expectStatus().isUnauthorized();
        client.get().uri(BASE + "/0").header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
            .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(messageSources, chunks);
    }

    @Test
    void hidesAnUnauthorizedMessageWithoutReadingItsSourceBody() {
        when(messageSources.findOwnedSources("default", "U1", SESSION, MESSAGE)).thenReturn(Optional.empty());
        for (String path : List.of(BASE, BASE + "/0")) {
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
                .expectStatus().isNotFound().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        verifyNoInteractions(chunks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1", "2147483647"})
    void rejectsIndexesOutsideTheSavedList(String index) {
        client.get().uri(BASE + "/" + index).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isNotFound();
        verifyNoInteractions(chunks);
    }

    @Test
    void oldMessagesDoNotInventSourcesFromModelText() {
        savedSources(List.of());
        client.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isOk().expectBody().json("[]");
        client.get().uri(BASE + "/0").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isNotFound();
        verifyNoInteractions(chunks);
    }

    @Test
    void repeatedToolNumbersDoNotChangeSavedIndexAndMissingReferencesStayUnavailable() {
        var reference = new KnowledgeDocumentReference(7L, 70L, 700L, 901L);
        var first = new KnowledgeRetrievalSource(1, "旧标题", "旧知识库", "901", null, reference);
        var missing = new KnowledgeRetrievalSource(1, "不可验证的标题", "不可验证的知识库", "901", null, null);
        var incomplete = new KnowledgeRetrievalSource(1, "不完整标题", "不完整知识库", "901", null,
            new KnowledgeDocumentReference(7L, null, 700L, 901L));
        savedSources(List.of(first, first, missing, incomplete));
        client.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[1].sourceIndex").isEqualTo(2)
            .jsonPath("$[1].status").isEqualTo("UNAVAILABLE")
            .jsonPath("$[1].title").isEmpty();
        for (int index : List.of(2, 3)) {
            client.get().uri(BASE + "/" + index).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UNAVAILABLE")
                .jsonPath("$.title").isEmpty().jsonPath("$.content").isEmpty();
        }
    }

    @Test
    void rechecksAccessAndClearsEveryMetadataFieldWhenSourceIsRevoked() {
        client.get().uri(BASE + "/0").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("AVAILABLE");
        when(chunks.findPublicSource("default", "7", 70L, 700L, 901L, true)).thenReturn(null);
        client.get().uri(BASE + "/0").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody().json("{\"sourceIndex\":0,\"status\":\"UNAVAILABLE\",\"title\":null,"
                + "\"knowledgeBase\":null,\"versionNo\":null,\"sourceVersion\":null,\"content\":null}", true);
    }

    @Test
    void bindsJwtTenantToBothStorageReadsAndRestoresTheBlockingContext() {
        when(messageSources.findOwnedSources("tenant-source", "U1", SESSION, MESSAGE)).thenAnswer(invocation -> {
            assertEquals("tenant-source", TenantContext.get());
            return Optional.of(List.of(new KnowledgeRetrievalSource(1, "线索", "policy", "901", null,
                new KnowledgeDocumentReference(7L, 70L, 700L, 901L))));
        });
        when(chunks.findPublicSource("tenant-source", "7", 70L, 700L, 901L, true)).thenAnswer(invocation -> {
            assertEquals("tenant-source", TenantContext.get());
            return new KnowledgePublicSource(901, "历史标题", "授权知识库", 3, "v1", "原文");
        });
        client.get().uri(BASE + "/0").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + jwt.issue("U1", "alice", "Alice", "tenant-source"))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("AVAILABLE");
    }

    @Test
    void storageFailureIsRetryableInsteadOfBeingReportedAsAnUnavailableSource() {
        when(chunks.findPublicSource("default", "7", 70L, 700L, 901L, true))
            .thenThrow(new DataAccessResourceFailureException("source database unavailable"));
        client.get().uri(BASE + "/0").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isEqualTo(503).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void messageEvidenceStorageFailureIsAlsoRetryable() {
        when(messageSources.findOwnedSources("default", "U1", SESSION, MESSAGE))
            .thenThrow(new DataAccessResourceFailureException("message database unavailable"));
        client.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isEqualTo(503).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        verifyNoInteractions(chunks);
    }

    @Test
    void missingOptionalKnowledgeStorageReturnsRetryableFailure() {
        var provider = new DefaultListableBeanFactory().getBeanProvider(KnowledgeChunkMapper.class);
        var service = new UserMessageSourceService(messageSources, provider);
        WebTestClient withoutStorage = WebTestClient.bindToController(new UserMessageSourceController(service))
            .webFilter(new UserAuthWebFilter(jwt)).build();
        withoutStorage.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isEqualTo(503);
    }

    @Test
    void missingOptionalMessageDataSourceReturnsRetryableFailure() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("chunks", chunks);
        var dao = new UserMessageSourceDao(beans.getBeanProvider(DataSource.class));
        var service = new UserMessageSourceService(dao, beans.getBeanProvider(KnowledgeChunkMapper.class));
        WebTestClient withoutStorage = WebTestClient.bindToController(new UserMessageSourceController(service))
            .webFilter(new UserAuthWebFilter(jwt)).build();
        withoutStorage.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
            .expectStatus().isEqualTo(503).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        verifyNoInteractions(chunks);
    }

    private void savedSources(List<KnowledgeRetrievalSource> sources) {
        when(messageSources.findOwnedSources("default", "U1", SESSION, MESSAGE)).thenReturn(Optional.of(sources));
    }

    private String bearer() {
        return "Bearer " + jwt.issue("U1", "alice", "Alice");
    }
}
