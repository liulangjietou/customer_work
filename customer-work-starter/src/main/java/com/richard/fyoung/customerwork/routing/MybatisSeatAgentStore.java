package com.richard.fyoung.customerwork.routing;

import com.richard.fyoung.customerwork.routing.entity.SeatAgentDO;
import com.richard.fyoung.customerwork.routing.mapper.SeatAgentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MyBatis-Plus 坐席库存储（生产实现：{@code customer-work.routing.seat-store-mode=jdbc} 时装配）。
 *
 * <p>坐席落 {@code cw_seat_agent} 表，多实例共享、支持后台运营维护。建表与种子由统一 SchemaInitializer 负责，
 * 本类只表达读写；DO ↔ 领域对象转换收敛在本层（{@code skills} 逗号串 ↔ Set）。异常一律 {@code catch(Exception)}
 * （HikariPool 初始化异常是 RuntimeException，非 SQLException 子类）；{@link #findAll} 读失败降级空集合
 * （fail-open：无候选 → 无推荐，坐席照常手动接单）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisSeatAgentStore implements SeatAgentStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisSeatAgentStore.class);

    private static final String SKILL_DELIMITER = ",";

    private final SeatAgentMapper mapper;

    public MybatisSeatAgentStore(SeatAgentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<SeatAgent> findAll() {
        try {
            return toDomainList(mapper.selectList(null));
        } catch (Exception e) {
            log.error("[MybatisSeatAgentStore] findAll failed, code={}", "SEAT-STORE-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public void save(SeatAgent seat) {
        if (seat == null || seat.getId() == null || seat.getId().isEmpty()) {
            return;
        }
        try {
            mapper.upsert(toDO(seat));
        } catch (Exception e) {
            log.error("[MybatisSeatAgentStore] save failed, code={}, id={}", "SEAT-STORE-SAVE-FAIL", seat.getId(), e);
            throw new IllegalStateException("failed to save seat agent: " + seat.getId(), e);
        }
    }

    private List<SeatAgent> toDomainList(List<SeatAgentDO> rows) {
        List<SeatAgent> result = new ArrayList<>(rows.size());
        for (SeatAgentDO row : rows) {
            result.add(toDomain(row));
        }
        return result;
    }

    private SeatAgent toDomain(SeatAgentDO row) {
        return new SeatAgent(
            row.getId(),
            row.getName(),
            parseSkills(row.getSkills()),
            row.getMaxLoad() == null ? 0 : row.getMaxLoad(),
            row.getCurrentLoad() == null ? 0 : row.getCurrentLoad(),
            row.getOnline() != null && row.getOnline(),
            row.getSeatGroup());
    }

    private SeatAgentDO toDO(SeatAgent seat) {
        SeatAgentDO row = new SeatAgentDO();
        row.setId(seat.getId());
        row.setName(seat.getName());
        row.setSkills(String.join(SKILL_DELIMITER, seat.getSkills()));
        row.setMaxLoad(seat.getMaxLoad());
        row.setCurrentLoad(seat.getCurrentLoad());
        row.setOnline(seat.isOnline());
        row.setSeatGroup(seat.getGroup());
        long now = System.currentTimeMillis();
        row.setCreatedAtMs(now);
        row.setUpdatedAtMs(now);
        return row;
    }

    private Set<String> parseSkills(String skills) {
        Set<String> result = new LinkedHashSet<>();
        if (StringUtils.hasText(skills)) {
            for (String s : skills.split(SKILL_DELIMITER)) {
                if (StringUtils.hasText(s)) {
                    result.add(s.trim());
                }
            }
        }
        return result;
    }
}
