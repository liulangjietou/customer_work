package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseOptionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseTestResult;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiAgentKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeBaseEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * RAG 知识库配置管理：CRUD + AppKey 加密存储 + 启停 + 连通性测试 + <b>保存门禁</b>。
 *
 * <p><b>保存门禁</b>（需求硬要求）：新建必测；编辑仅在连接参数（baseUrl/appId/apiKey/contentType/
 * extraHeaders）真正发生变更时才测——只改名称/备注/阈值/启停不重测，避免每次改个备注都去打一次外部
 * 服务、也避免外部服务临时抖动时连改备注都改不了。实测不通过直接抛 {@link BizException} 阻止保存
 * （同 {@code AgentService#assertPrimaryModelConnectivity} 的现场实测模式，不读 test_status 字段）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class KnowledgeBaseService {


    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** 连通性测试专用线程池：与 Tomcat 请求线程隔离，低频操作，池子不需要大（同 ModelConfigService）。 */
    private static final ExecutorService TEST_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "kb-test-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final long TEST_FUTURE_TIMEOUT_SECONDS = 10;
    private static final String CODE_TEST_FAIL = "KB-TEST-FAIL";
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final KnowledgeSearchClient searchClient;
    private final AdminRagProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeBaseService(AiKnowledgeBaseMapper knowledgeBaseMapper,
                                 AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper,
                                 AesGcmCryptoUtil cryptoUtil, KnowledgeSearchClient searchClient,
                                 AdminRagProperties properties) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.agentKnowledgeBaseMapper = agentKnowledgeBaseMapper;
        this.cryptoUtil = cryptoUtil;
        this.searchClient = searchClient;
        this.properties = properties;
    }

    public PageResult<KnowledgeBaseVO> page(PageQuery query) {
        LambdaQueryWrapper<AiKnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiKnowledgeBase::getKbName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiKnowledgeBase::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiKnowledgeBase::getCreateTime);

        IPage<AiKnowledgeBase> page = knowledgeBaseMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public KnowledgeBaseVO get(Long id) {
        return toVo(requireKnowledgeBase(id));
    }

    /** 智能体表单下拉：只给可用的知识库（启用 + 连通性测试成功），不可用的不允许被绑定。 */
    public List<KnowledgeBaseOptionVO> options() {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<AiKnowledgeBase>()
                .eq(AiKnowledgeBase::getStatus, StatusFlags.ENABLED)
                .eq(AiKnowledgeBase::getTestStatus, ConnectivityTestStatus.SUCCESS)
                .orderByAsc(AiKnowledgeBase::getId))
            .stream()
            .map(kb -> new KnowledgeBaseOptionVO(kb.getId(), kb.getKbName()))
            .collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(KnowledgeBaseSaveRequest request) {
        if (!StringUtils.hasText(request.apiKey())) {
            throw new BizException(ResultCode.PARAM_MISSING, "新建知识库必须提供 apiKey");
        }
        assertNameAvailable(request.kbName(), null);
        validateExtraHeaders(request.extraHeaders());

        AiKnowledgeBase entity = new AiKnowledgeBase();
        fillFromRequest(entity, request);
        entity.setApiKey(cryptoUtil.encrypt(request.apiKey()));
        // 保存门禁：现场实测，不通过直接抛异常阻止保存
        KnowledgeBaseTestResult result = assertConnectivity(toEndpoint(entity, request.apiKey()));
        entity.setTestStatus(result.testStatus());
        entity.setTestTime(result.testTime());

        // 坑：ai_knowledge_base.uk_ai_kb_name 是不含 deleted 列的纯数据库唯一约束，而 delete() 走的是
        // 逻辑删除（@TableLogic 只把 deleted 置 1，行还在、名字还占着）。上面的 assertNameAvailable 走
        // LambdaQueryWrapper 会被自动追加 deleted=0，于是判定"名称可用"，但直接 INSERT 必然撞唯一约束抛
        // DuplicateKeyException（与 AuthService#findOrCreateLdapUser 完全同款的坑）。这里先看有没有被软
        // 删除过的同名旧行，有就"复活"它再整体覆盖字段，而不是插新行——若只把异常兜成友好提示，一个名字
        // 被删过一次就永久不能再用，那是功能缺陷而不只是错误信息不友好。复活复用旧行 id，
        // create_by/create_time 保留旧值（与 AuthService 的复活语义一致，不额外重置）。
        AiKnowledgeBase softDeleted = knowledgeBaseMapper.selectDeletedByName(request.kbName());
        if (softDeleted != null) {
            knowledgeBaseMapper.reviveDeleted(softDeleted.getId());
            entity.setId(softDeleted.getId());
            knowledgeBaseMapper.updateById(entity);
            log.info("[rag] revived soft-deleted knowledge base for re-create, kbName={}, kbId={}",
                request.kbName(), softDeleted.getId());
            return;
        }
        // 纯并发竞争（两个请求同时创建同名知识库，都没查到对方尚未提交的行）仍可能撞唯一键：兜成友好的
        // 业务异常，不让 DuplicateKeyException 裸奔到 GlobalExceptionHandler 变成不明所以的 SYSTEM_ERROR。
        try {
            knowledgeBaseMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "知识库名称已存在: " + request.kbName());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, KnowledgeBaseSaveRequest request) {
        AiKnowledgeBase entity = requireKnowledgeBase(id);
        assertNameAvailable(request.kbName(), id);
        validateExtraHeaders(request.extraHeaders());

        // 连接参数是否变更要在字段被覆盖前判断；apiKey 留空=沿用旧值，故不算变更
        boolean connectionChanged = isConnectionChanged(entity, request);
        String plainApiKey = StringUtils.hasText(request.apiKey())
            ? request.apiKey() : cryptoUtil.decrypt(entity.getApiKey());

        fillFromRequest(entity, request);
        if (StringUtils.hasText(request.apiKey())) {
            entity.setApiKey(cryptoUtil.encrypt(request.apiKey()));
        }
        if (connectionChanged) {
            KnowledgeBaseTestResult result = assertConnectivity(toEndpoint(entity, plainApiKey));
            entity.setTestStatus(result.testStatus());
            entity.setTestTime(result.testTime());
        }
        // 改名撞上被软删除的同名旧行同样会抛 DuplicateKeyException（原因同 create 里的注释）。改名场景
        // 不能走"复活"（那会变成两行同名），只能给出友好提示让用户换个名字。
        try {
            knowledgeBaseMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "知识库名称已存在: " + request.kbName());
        }
    }

    public void delete(Long id) {
        requireKnowledgeBase(id);
        if (agentKnowledgeBaseMapper.exists(new LambdaQueryWrapper<AiAgentKnowledgeBase>()
            .eq(AiAgentKnowledgeBase::getKnowledgeBaseId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该知识库正被智能体引用，无法删除");
        }
        knowledgeBaseMapper.deleteById(id);
    }

    /** 启用/停用（生命周期），不改动其余字段。停用后运行时立即不再参与检索。 */
    public void updateStatus(Long id, int status) {
        requireKnowledgeBase(id);
        AiKnowledgeBase update = new AiKnowledgeBase();
        update.setId(id);
        update.setStatus(status);
        knowledgeBaseMapper.updateById(update);
    }

    /**
     * 连通性测试：用固定探测语句真实发一次检索请求，派发到独立线程池执行并硬性超时兜底，
     * 不占用调用方（Tomcat）线程。Controller 侧以 {@link CompletableFuture} 异步返回。
     */
    public CompletableFuture<KnowledgeBaseTestResult> testConnectivity(Long id) {
        AiKnowledgeBase entity = requireKnowledgeBase(id);
        KnowledgeBaseEndpoint endpoint = toEndpoint(entity, cryptoUtil.decrypt(entity.getApiKey()));

        return CompletableFuture
            .supplyAsync(() -> probe(endpoint), TEST_EXECUTOR)
            .orTimeout(TEST_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex.getCause() instanceof TimeoutException || ex instanceof TimeoutException) {
                    log.error("knowledge base connectivity test hard-timeout, code={}, kbId={}",
                        "KB-TEST-HARD-TIMEOUT", id, ex);
                } else {
                    log.error("knowledge base connectivity test unexpected error, code={}, kbId={}",
                        "KB-TEST-UNEXPECTED", id, ex);
                }
                return KnowledgeBaseTestResult.failed("连通性测试超时或执行异常");
            })
            .thenApply(result -> {
                persistTestResult(id, result);
                return result;
            });
    }

    /** 真实发一次探测检索：成功返回命中条数，失败转成失败态结果（不抛异常，供异步链路统一处理）。 */
    private KnowledgeBaseTestResult probe(KnowledgeBaseEndpoint endpoint) {
        try {
            return KnowledgeBaseTestResult.success(
                searchClient.searchOne(endpoint, properties.getTestQuery()).size());
        } catch (Exception e) {
            log.error("knowledge base probe failed, code={}, kbId={}", CODE_TEST_FAIL, endpoint.id(), e);
            return KnowledgeBaseTestResult.failed(e.getMessage());
        }
    }

    /**
     * 保存门禁：同步实测连通性，非成功即抛业务异常阻止保存。走请求线程直连——
     * {@link KnowledgeSearchClient} 的请求级超时（{@code admin.rag.request-timeout-seconds}，默认 8s）
     * 已经把等待时间兜死，不需要再叠一层线程池 + future 超时（新建场景也还没有 id 可供
     * {@link #testConnectivity} 使用）。
     */
    private KnowledgeBaseTestResult assertConnectivity(KnowledgeBaseEndpoint endpoint) {
        KnowledgeBaseTestResult result = probe(endpoint);
        if (result.testStatus() != ConnectivityTestStatus.SUCCESS) {
            throw new BizException(ResultCode.KNOWLEDGE_BASE_TEST_FAILED,
                "知识库连通性测试未通过: " + result.message());
        }
        log.info("[rag] knowledge base connectivity gate passed, kbName={}, hitCount={}",
            endpoint.kbName(), result.hitCount());
        return result;
    }

    private void persistTestResult(Long id, KnowledgeBaseTestResult result) {
        AiKnowledgeBase update = new AiKnowledgeBase();
        update.setId(id);
        update.setTestStatus(result.testStatus());
        update.setTestTime(result.testTime());
        knowledgeBaseMapper.updateById(update);
    }

    /** 名称唯一（库上有唯一索引，这里提前查一次给出可读错误，避免抛出裸的约束冲突异常）。 */
    private void assertNameAvailable(String kbName, Long selfId) {
        LambdaQueryWrapper<AiKnowledgeBase> wrapper = new LambdaQueryWrapper<AiKnowledgeBase>()
            .eq(AiKnowledgeBase::getKbName, kbName);
        if (selfId != null) {
            wrapper.ne(AiKnowledgeBase::getId, selfId);
        }
        if (knowledgeBaseMapper.exists(wrapper)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "知识库名称已存在: " + kbName);
        }
    }

    /** 自定义请求头保存时校验一次（fast fail），运行时不再重复校验规则。 */
    private void validateExtraHeaders(String extraHeaders) {
        try {
            KnowledgeSearchClient.parseExtraHeaders(objectMapper, extraHeaders);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, e.getMessage());
        }
    }

    /** 连接参数（决定"能不能连上"的那几项）是否变更；apiKey 留空视为不改，不算变更。 */
    private boolean isConnectionChanged(AiKnowledgeBase entity, KnowledgeBaseSaveRequest request) {
        return StringUtils.hasText(request.apiKey())
            || !Objects.equals(entity.getBaseUrl(), request.baseUrl())
            || !Objects.equals(entity.getAppId(), request.appId())
            || !Objects.equals(entity.getContentType(), normalizeContentType(request.contentType()))
            || !Objects.equals(entity.getExtraHeaders(), normalizeExtraHeaders(request.extraHeaders()));
    }

    private void fillFromRequest(AiKnowledgeBase entity, KnowledgeBaseSaveRequest request) {
        entity.setKbName(request.kbName());
        entity.setBaseUrl(request.baseUrl());
        entity.setAppId(request.appId());
        entity.setContentType(normalizeContentType(request.contentType()));
        entity.setExtraHeaders(normalizeExtraHeaders(request.extraHeaders()));
        entity.setTopN(request.topN() == null || request.topN() <= 0
            ? KnowledgeBaseEndpoint.DEFAULT_TOP_N : request.topN());
        entity.setScoreThreshold(request.scoreThreshold() == null ? BigDecimal.ZERO : request.scoreThreshold());
        entity.setStatus(request.status() == null ? StatusFlags.ENABLED : request.status());
        entity.setRemark(request.remark());
    }

    private String normalizeContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : KnowledgeBaseEndpoint.DEFAULT_CONTENT_TYPE;
    }

    private String normalizeExtraHeaders(String extraHeaders) {
        return StringUtils.hasText(extraHeaders) ? extraHeaders : "";
    }

    /** 实体 + 明文 apiKey → 检索端点参数（供门禁/探测/运行时检索共用同一份组装逻辑）。 */
    private KnowledgeBaseEndpoint toEndpoint(AiKnowledgeBase entity, String plainApiKey) {
        return new KnowledgeBaseEndpoint(entity.getId(), entity.getKbName(), entity.getBaseUrl(), entity.getAppId(),
            plainApiKey, entity.getContentType(), entity.getExtraHeaders(), entity.getTopN(),
            entity.getScoreThreshold());
    }

    private KnowledgeBaseVO toVo(AiKnowledgeBase entity) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(entity.getId());
        vo.setKbName(entity.getKbName());
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setAppId(entity.getAppId());
        vo.setApiKeyMasked(AesGcmCryptoUtil.mask(cryptoUtil.decrypt(entity.getApiKey())));
        vo.setContentType(entity.getContentType());
        vo.setExtraHeaders(entity.getExtraHeaders());
        vo.setTopN(entity.getTopN());
        vo.setScoreThreshold(entity.getScoreThreshold());
        vo.setStatus(entity.getStatus());
        vo.setTestStatus(entity.getTestStatus());
        vo.setTestTime(entity.getTestTime());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    /** 知识库存在性校验（本模块唯一防御点）。 */
    private AiKnowledgeBase requireKnowledgeBase(Long id) {
        AiKnowledgeBase entity = knowledgeBaseMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在: " + id);
        }
        return entity;
    }
}
