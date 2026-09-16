import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.data.chatlog.ChatAnswerEvidence;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.MybatisChatMessageStore;
import com.richard.fyoung.customerwork.data.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.data.ticket.MybatisTicketStore;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketQuery;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/** 查询和浏览器性能共用的自有数据集；关闭时只删除本实例成功创建的随机库。 */
public final class TicketPerformanceDataset implements AutoCloseable {
    public static final String TENANT = "performance-owned";
    public static final String OTHER_TENANT = "performance-other";
    public static final int OWN_ROWS = 10_000;
    public static final int OTHER_ROWS = 500;
    public static final int MESSAGE_COUNT = 200;
    public static final int PAGE_SIZE = 20;
    public static final List<Integer> CONVERSATIONS = List.of(9998, 9996);
    private static final long BASE_TIME = 1789400000000L;
    private final String database = "cw_perf_" + UUID.randomUUID().toString().replace("-", "");
    private final String server = "jdbc:mysql://" + System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1")
        + ":" + System.getenv().getOrDefault("MYSQL_PORT", "3306") + "/";
    private final String options = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8"
        + "&serverTimezone=UTC&rewriteBatchedStatements=true";
    private final String user = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private final String password = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private final Path output;
    private HikariDataSource source;
    private boolean created;
    private MybatisTicketStore tickets;
    private ChatLogService messages;

    private TicketPerformanceDataset(Path output) {
        this.output = output;
    }

    /** 使用生产 Flyway、租户插件和分页插件创建固定规模数据；初始化失败同样执行清理。 */
    public static TicketPerformanceDataset create(Path output) throws Exception {
        Files.createDirectories(output);
        var dataset = new TicketPerformanceDataset(output);
        try {
            dataset.initialize();
            return dataset;
        } catch (Exception error) {
            try {
                dataset.close();
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private void initialize() throws Exception {
        try (Connection connection = DriverManager.getConnection(server + options, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            created = true;
        }
        // 保留所属库名，进程被强制终止时可以按明确归属恢复；绝不扫描或删除其他数据库。
        Files.writeString(output.resolve("owned-database.txt"), database + "\n");
        var properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getSession().getMysql().setJdbcUrl(server + database + options);
        properties.getSession().getMysql().setUsername(user);
        properties.getSession().getMysql().setPassword(password);
        var factory = new CustomerWorkPersistenceConfig();
        source = (HikariDataSource) factory.customerWorkDataSource(properties);
        Flyway.configure().dataSource(source).locations("classpath:db/customerwork/migration")
            .placeholderReplacement(false).load().migrate();
        try (Connection connection = source.getConnection()) {
            seedTickets(connection, TENANT, OWN_ROWS);
            seedTickets(connection, OTHER_TENANT, OTHER_ROWS);
        }
        var sessionFactory = factory.customerWorkSqlSessionFactory(source, properties);
        // 独立测量器没有 Spring MapperScan；XML 会注册部分 Mapper，剩余注解 Mapper 显式补齐。
        for (Class<?> mapper : List.of(TicketMapper.class, TicketEventMapper.class, ChatMessageMapper.class)) {
            if (!sessionFactory.getConfiguration().hasMapper(mapper)) {
                sessionFactory.getConfiguration().addMapper(mapper);
            }
        }
        var template = factory.customerWorkSqlSessionTemplate(sessionFactory);
        tickets = new MybatisTicketStore(template.getMapper(TicketMapper.class), template.getMapper(TicketEventMapper.class));
        messages = new ChatLogService(new MybatisChatMessageStore(template.getMapper(ChatMessageMapper.class)));
        TenantContext.runWith(TENANT, () -> {
            for (int number : CONVERSATIONS) seedMessages(number);
            verify(tickets.findPage(new TicketQuery(null, null, null, null, null, 1, PAGE_SIZE)).total() == OWN_ROWS,
                "Foreign tenant affected owned total");
        });
        TenantContext.runWith(OTHER_TENANT, () -> {
            verify(tickets.findPage(new TicketQuery(null, null, null, null, null, 1, PAGE_SIZE)).total() == OTHER_ROWS,
                "Foreign tenant control failed");
            verify(messages.historyByTicket(TENANT + "-9998", null, MESSAGE_COUNT).isEmpty(),
                "Conversation leaked across tenants");
        });
    }

    private void seedMessages(int number) {
        String ticket = TENANT + "-" + number;
        String session = TENANT + "-session-" + number;
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            String content = "[" + ticket + ":" + i + "] 售后进度核对：订单已收到，正在确认处理记录。";
            if (i % 10 == 0) content += "\n请核对收件信息、退款进度与物流状态；保留这段较长的历史说明。".repeat(24);
            if (i % 3 == 0) {
                var citation = new KnowledgeCitation("售后政策", "policy-" + (i % 5), "chunk-" + i, 0.93);
                messages.appendAnswer(session, ticket, content,
                    new ChatAnswerEvidence("stop", List.of(citation), List.of(), List.of()));
            } else {
                messages.append(session, ticket, i % 3 == 1 ? TicketActorType.USER : TicketActorType.AGENT,
                    i % 3 == 1 ? "user-" + number : "agent-8", content);
            }
        }
        verify(messages.historyByTicket(ticket, null, MESSAGE_COUNT + 1).size() == MESSAGE_COUNT,
            "Unexpected seeded conversation size");
    }

    private static void seedTickets(Connection connection, String tenant, int rows) throws Exception {
        String sql = "INSERT INTO cw_ticket(tenant_id,id,session_id,user_id,title,category,priority,status,assignee,created_at_ms,updated_at_ms)"
            + " VALUES (?,?,?,?,?,'AFTER_SALE','NORMAL',?,?,?,?)";
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < rows; i++) {
                statement.setString(1, tenant);
                statement.setString(2, tenant + "-" + i);
                statement.setString(3, tenant + "-session-" + i);
                statement.setString(4, "user-" + i);
                statement.setString(5, "售后进度与历史咨询 " + i);
                statement.setString(6, i % 2 == 0 ? "PROCESSING" : "WAITING_AGENT");
                statement.setString(7, "agent-" + (i % 10));
                statement.setLong(8, BASE_TIME + i);
                statement.setLong(9, BASE_TIME + i);
                statement.addBatch();
                if ((i + 1) % 500 == 0) statement.executeBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public MybatisTicketStore tickets() { return tickets; }
    public ChatLogService messages() { return messages; }

    /** 关闭数据源后删除自有库；清理成功才写回证据，失败向调用方传播。 */
    @Override
    public synchronized void close() throws Exception {
        if (source != null) source.close();
        if (!created) return;
        try (Connection connection = DriverManager.getConnection(server + options, user, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
        created = false;
        Files.writeString(output.resolve("owned-database-cleaned.txt"), database + "\n");
    }

    private static void verify(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
