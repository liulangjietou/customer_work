package com.richard.fyoung.customerwork.slotfilling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC 槽位收集进度存储（生产参考实现：补齐 {@code slot-filling.store-mode=jdbc} 的持久化落地）。
 *
 * <p>把收集进度结构化写入 {@code cw_slot_filling_progress} 表，保证应用重启 / 多实例部署下
 * 正在进行的多轮信息收集（如退款表单：订单号→原因）不丢失、用户无需从头重答。</p>
 *
 * <p>由 {@link SlotFillingConfig} 按 {@code slot-filling.store-mode=jdbc} 自动装配，复用
 * {@code session.mysql.*} 的连接配置构建独立连接池。建表 SQL 见
 * {@code mysql/schema.sql} 中的 {@code cw_slot_filling_progress} 表。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcSlotFillingStore implements SlotFillingStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSlotFillingStore.class);

    private static final String UPSERT_SQL =
        "INSERT INTO cw_slot_filling_progress (progress_key, asking, collected_json) VALUES (?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE asking=VALUES(asking), collected_json=VALUES(collected_json)";

    private static final String SELECT_SQL =
        "SELECT asking, collected_json FROM cw_slot_filling_progress WHERE progress_key = ?";

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcSlotFillingStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public void save(String key, SlotFillingProgress progress) {
        if (key == null || progress == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, key);
            ps.setString(2, progress.getAsking());
            ps.setString(3, mapper.writeValueAsString(progress.getCollected()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[JdbcSlotFillingStore] save failed, errorCode={}, key={}",
                "SLOTFILL-STORE-SAVE-FAIL", key, e);
            throw new IllegalStateException("failed to save slot-filling progress: " + key, e);
        }
    }

    @Override
    public Optional<SlotFillingProgress> find(String key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                SlotFillingProgress progress = new SlotFillingProgress();
                progress.setAsking(rs.getString("asking"));
                String json = rs.getString("collected_json");
                if (json != null && !json.isBlank()) {
                    Map<String, String> collected = mapper.readValue(json, new TypeReference<Map<String, String>>() { });
                    progress.getCollected().putAll(collected);
                }
                return Optional.of(progress);
            }
        } catch (Exception e) {
            log.error("[JdbcSlotFillingStore] find failed, errorCode={}, key={}",
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cw_slot_filling_progress WHERE progress_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[JdbcSlotFillingStore] delete failed, errorCode={}, key={}",
                "SLOTFILL-STORE-DELETE-FAIL", key, e);
        }
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_slot_filling_progress (
                progress_key VARCHAR(191) PRIMARY KEY COMMENT '收集进度键：sessionId:formName',
                asking VARCHAR(64) COMMENT '当前追问的槽位名',
                collected_json TEXT COMMENT '已收集槽位值（JSON）'
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多轮槽位收集进度（如退款表单信息采集）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("[JdbcSlotFillingStore] 收集进度表已就绪: cw_slot_filling_progress");
        } catch (Exception e) {
            // 捕获 Exception：连接池首次取连接失败（MySQL 冷启动瞬时不可达）抛出的是
            // HikariPool$PoolInitializationException（RuntimeException），非 SQLException
            log.error("[JdbcSlotFillingStore] ensureTable failed, errorCode={}", "SLOTFILL-STORE-DDL-FAIL", e);
        }
    }
}
