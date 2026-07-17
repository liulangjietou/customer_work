package com.richard.fyoung.customerwork.slotfilling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.slotfilling.entity.SlotFillingProgressDO;
import com.richard.fyoung.customerwork.slotfilling.mapper.SlotFillingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * MyBatis-Plus 槽位收集进度存储（生产实现：补齐 {@code slot-filling.store-mode=jdbc} 的持久化落地）。
 *
 * <p>把收集进度结构化写入 {@code cw_slot_filling_progress} 表，保证应用重启 / 多实例部署下
 * 正在进行的多轮信息收集（如退款表单：订单号→原因）不丢失、用户无需从头重答。</p>
 *
 * <p>由 {@link SlotFillingConfig} 按 {@code slot-filling.store-mode=jdbc} 装配；建表由
 * {@code SchemaInitializer} 统一负责。已收集槽位值以 JSON 与 {@code collected_json} 列互转。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisSlotFillingStore implements SlotFillingStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisSlotFillingStore.class);

    private final SlotFillingMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MybatisSlotFillingStore(SlotFillingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(String key, SlotFillingProgress progress) {
        if (key == null || progress == null) {
            return;
        }
        try {
            SlotFillingProgressDO entity = new SlotFillingProgressDO();
            entity.setProgressKey(key);
            entity.setAsking(progress.getAsking());
            entity.setCollectedJson(objectMapper.writeValueAsString(progress.getCollected()));
            mapper.upsert(entity);
        } catch (Exception e) {
            log.error("[MybatisSlotFillingStore] save failed, errorCode={}, key={}",
                "SLOTFILL-STORE-SAVE-FAIL", key, e);
            throw new IllegalStateException("failed to save slot-filling progress: " + key, e);
        }
    }

    @Override
    public Optional<SlotFillingProgress> find(String key) {
        try {
            SlotFillingProgressDO entity = mapper.selectById(key);
            if (entity == null) {
                return Optional.empty();
            }
            SlotFillingProgress progress = new SlotFillingProgress();
            progress.setAsking(entity.getAsking());
            String json = entity.getCollectedJson();
            if (json != null && !json.isBlank()) {
                Map<String, String> collected = objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
                progress.getCollected().putAll(collected);
            }
            return Optional.of(progress);
        } catch (Exception e) {
            log.error("[MybatisSlotFillingStore] find failed, errorCode={}, key={}",
                "SLOTFILL-STORE-FIND-FAIL", key, e);
            return Optional.empty();
        }
    }

    @Override
    public SlotFillingProgress findOrCreate(String key) {
        return find(key).orElseGet(SlotFillingProgress::new);
    }

    @Override
    public void delete(String key) {
        try {
            mapper.deleteById(key);
        } catch (Exception e) {
            log.error("[MybatisSlotFillingStore] delete failed, errorCode={}, key={}",
                "SLOTFILL-STORE-DELETE-FAIL", key, e);
        }
    }
}
