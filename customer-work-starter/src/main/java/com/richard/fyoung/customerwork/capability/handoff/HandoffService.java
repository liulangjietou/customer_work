package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.capability.routing.HandoffCreatedEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * 人机切换工单服务（AI→人工接管→人工→AI 回收 的应用层闭环）。
 *
 * <p>取代 {@code HumanHandoffTools.transferToHuman} 此前"只打日志 + 生成随机字符串工单号"的空实现——
 * 转人工不再是一句无状态的话术，而是一张可查询、可流转的 {@link HandoffTicket}：AI 转出生成
 * {@code PENDING} 工单 → 坐席 {@link #claim} 接单（{@code CLAIMED}）→ 坐席处理完毕
 * {@link #resolve}（{@code RESOLVED}，会话可回收给 AI 续接）。</p>
 *
 * <p>存储委托给 {@link HandoffStore} SPI：默认 {@link InMemoryHandoffStore}（进程内，离线可测），
 * 生产可声明自己的 {@link HandoffStore} Bean（如 JDBC / Redis 实现）覆盖默认，保证工单重启不丢失。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class HandoffService {

    private static final Logger log = LoggerFactory.getLogger(HandoffService.class);

    private static final String ID_PREFIX = "HO-";

    private final HandoffStore store;

    /**
     * 转人工增强器（智能路由中控·会话总结 + 工单智能分配）：<b>可选</b>，setter 注入。
     *
     * <p>用 setter 而非构造注入：一是保留既有无参/单参构造不破坏旧测试；二是打破
     * {@code HandoffService ⇆ HandoffCreatedEnricher}（增强器构造依赖本服务做推荐回写）的循环依赖。
     * 未装配（未开启增强 / 纯工具场景）时建单行为与此前完全一致。</p>
     */
    private HandoffCreatedEnricher enricher;

    /**
     * Spring 注入构造：使用自动装配的 {@link HandoffStore} Bean（memory 模式为 {@link InMemoryHandoffStore}，
     * jdbc 模式为 {@link MybatisHandoffStore}，均由 {@link HandoffConfig} 提供）。
     *
     * <p>必须标 {@code @Autowired}：本类同时存在无参构造，Spring 对"多构造器 + 存在无参 + 无
     * {@code @Autowired}"会回退到无参构造 → 永远用内存实现、{@code human-handoff.store-mode=jdbc} 空转。
     * 显式标注让容器选中本构造，真正注入配置好的 Store Bean。</p>
     */
    @Autowired
    public HandoffService(HandoffStore store) {
        this.store = store;
    }

    /** 可选注入转人工增强器（不存在则不增强，建单行为不变）。 */
    @Autowired(required = false)
    public void setEnricher(HandoffCreatedEnricher enricher) {
        this.enricher = enricher;
    }

    /** 无参构造（兼容旧测试与无 Spring 场景）：使用默认内存存储。 */
    public HandoffService() {
        this.store = new InMemoryHandoffStore();
    }

    /** AI 转出：登记一张待接单工单（PENDING）。 */
    public HandoffTicket create(String sessionId, String reason) {
        String id = ID_PREFIX + UUID.randomUUID();
        HandoffTicket ticket = new HandoffTicket(id, sessionId, reason, System.currentTimeMillis());
        store.save(ticket);
        log.info("handoff created: id={}, session={}, reason={}", id, sessionId, reason);
        fireEnrichment(ticket);
        return ticket;
    }

    /**
     * 触发转人工增强（会话摘要预生成 + 工单分类打分推荐），<b>异步、fail-open</b>：增强器内部把耗时工作派发到
     * 独立线程池，此处仅同步派发。摘要/推荐是增强，挂了不能影响转人工——故即便派发本身异常也只 error 不抛。
     */
    private void fireEnrichment(HandoffTicket ticket) {
        if (enricher == null) {
            return;
        }
        try {
            enricher.onHandoffCreated(ticket);
        } catch (Exception e) {
            log.error("[HandoffService] enrichment trigger failed, code={}, id={}",
                "HANDOFF-ENRICH-TRIGGER-FAIL", ticket.getId(), e);
        }
    }

    /**
     * 回写工单智能分配的分类与推荐结果（由 {@code HandoffCreatedEnricher} 异步调用）。
     *
     * <p>走本服务自身的 {@link HandoffStore}，保证与坐席工作台经本服务读到的是同一份工单。工单不存在时只
     * error 记录、不抛（fail-open：增强回写迟到于工单已被清理等边界情况不应产生异常）。</p>
     */
    public void applyRoutingSuggestion(String id, String category, String requiredSkill,
                                       String priority, String emotion, String suggestedAssignees) {
        Optional<HandoffTicket> found = store.find(id);
        if (found.isEmpty()) {
            log.error("[HandoffService] apply routing suggestion skipped (ticket not found), code={}, id={}",
                "HANDOFF-ROUTING-APPLY-MISS", id);
            return;
        }
        HandoffTicket ticket = found.get();
        ticket.applyRoutingSuggestion(category, requiredSkill, priority, emotion, suggestedAssignees);
        store.update(ticket);
        log.info("handoff routing suggestion applied: id={}, category={}, priority={}", id, category, priority);
    }

    /** 全部工单（含已结案）。 */
    public List<HandoffTicket> list() {
        return store.findAll();
    }

    /** 按状态过滤（如只看 PENDING 待接单）。 */
    public List<HandoffTicket> listByStatus(HandoffStatus status) {
        return store.findByStatus(status);
    }

    public Optional<HandoffTicket> find(String id) {
        return store.find(id);
    }

    /** 坐席接单：仅 PENDING 可推进，重复接单 fast-fail。 */
    public HandoffTicket claim(String id, String operator) {
        HandoffTicket ticket = require(id);
        ticket.claim(operator, System.currentTimeMillis());
        store.update(ticket);
        log.info("handoff claimed: id={}, operator={}", id, operator);
        return ticket;
    }

    /** 坐席处理完毕、回收给 AI：仅 CLAIMED 可推进，未接单先结案 fast-fail。 */
    public HandoffTicket resolve(String id, String note) {
        HandoffTicket ticket = require(id);
        ticket.resolve(note, System.currentTimeMillis());
        store.update(ticket);
        log.info("handoff resolved: id={}, note={}", id, note);
        return ticket;
    }

    /** 单一防御点：工单必须存在，否则 fast-fail。 */
    private HandoffTicket require(String id) {
        return store.find(id).orElseThrow(() ->
            new NoSuchElementException("handoff not found: " + id));
    }
}
