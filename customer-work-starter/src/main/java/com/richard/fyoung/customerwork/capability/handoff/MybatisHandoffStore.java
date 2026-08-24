package com.richard.fyoung.customerwork.capability.handoff;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.capability.handoff.entity.HandoffTicketDO;
import com.richard.fyoung.customerwork.capability.handoff.mapper.HandoffMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 历史人机切换工单存储（仅旧表迁移回归测试）。
 *
 * <p>P1-03 起生产不再装配本类，{@code cw_handoff_ticket} 只读归档；权威状态统一写入
 * {@code cw_ticket}。保留本类是为了验证 V17 迁移前的旧数据读写合同。</p>
 *
 * <p>DO ↔ 领域对象转换收敛在本层：写入前 {@link #toDO} 拆解充血实体为贫血 DO，读出后
 * {@link #toDomain} 经 {@link HandoffTicket#reconstruct} 跳过状态机校验重建。异常一律
 * {@code catch(Exception)}（HikariPool 初始化异常是 RuntimeException，非 SQLException 子类）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisHandoffStore implements HandoffStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisHandoffStore.class);

    private final HandoffMapper mapper;

    public MybatisHandoffStore(HandoffMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(HandoffTicket ticket) {
        if (ticket == null || ticket.getId() == null) {
            return;
        }
        try {
            mapper.upsert(toDO(ticket));
        } catch (Exception e) {
            log.error("[MybatisHandoffStore] save failed, errorCode={}, id={}",
                "HANDOFF-STORE-SAVE-FAIL", ticket.getId(), e);
            throw new IllegalStateException("failed to save handoff ticket: " + ticket.getId(), e);
        }
    }

    @Override
    public void update(HandoffTicket ticket) {
        save(ticket);
    }

    @Override
    public Optional<HandoffTicket> find(String id) {
        try {
            HandoffTicketDO row = mapper.selectById(id);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisHandoffStore] find failed, errorCode={}, id={}", "HANDOFF-STORE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public List<HandoffTicket> findAll() {
        try {
            return toDomainList(mapper.selectList(null));
        } catch (Exception e) {
            log.error("[MybatisHandoffStore] findAll failed, errorCode={}", "HANDOFF-STORE-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public List<HandoffTicket> findByStatus(HandoffStatus status) {
        try {
            QueryWrapper<HandoffTicketDO> wrapper = new QueryWrapper<HandoffTicketDO>().eq("status", status.name());
            return toDomainList(mapper.selectList(wrapper));
        } catch (Exception e) {
            log.error("[MybatisHandoffStore] findByStatus failed, errorCode={}, status={}",
                "HANDOFF-STORE-FINDBYSTATUS-FAIL", status, e);
            return List.of();
        }
    }

    private List<HandoffTicket> toDomainList(List<HandoffTicketDO> rows) {
        List<HandoffTicket> result = new ArrayList<>(rows.size());
        for (HandoffTicketDO row : rows) {
            result.add(toDomain(row));
        }
        return result;
    }

    private HandoffTicket toDomain(HandoffTicketDO row) {
        return HandoffTicket.reconstruct(
            row.getId(),
            row.getSessionId(),
            row.getReason(),
            row.getCreatedAtMs(),
            HandoffStatus.valueOf(row.getStatus()),
            row.getClaimedBy(),
            row.getClaimedAtMs(),
            row.getResolutionNote(),
            row.getResolvedAtMs(),
            row.getCategory(),
            row.getRequiredSkill(),
            row.getPriority(),
            row.getEmotion(),
            row.getSuggestedAssignees());
    }

    private HandoffTicketDO toDO(HandoffTicket ticket) {
        HandoffTicketDO row = new HandoffTicketDO();
        row.setId(ticket.getId());
        row.setSessionId(ticket.getSessionId());
        row.setReason(ticket.getReason());
        row.setCreatedAtMs(ticket.getCreatedAtMs());
        row.setStatus(ticket.getStatus().name());
        row.setClaimedBy(ticket.getClaimedBy());
        row.setClaimedAtMs(ticket.getClaimedAtMs());
        row.setResolutionNote(ticket.getResolutionNote());
        row.setResolvedAtMs(ticket.getResolvedAtMs());
        row.setCategory(ticket.getCategory());
        row.setRequiredSkill(ticket.getRequiredSkill());
        row.setPriority(ticket.getPriority());
        row.setEmotion(ticket.getEmotion());
        row.setSuggestedAssignees(ticket.getSuggestedAssignees());
        return row;
    }
}
