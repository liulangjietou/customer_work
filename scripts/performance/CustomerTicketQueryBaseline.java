import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.data.ticket.MybatisTicketStore;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketQuery;
import com.richard.fyoung.customerwork.data.ticket.TicketStatus;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 独立库查询基线，记录生产 count、分页和对象映射；HTTP 延迟另由浏览器采样。 */
public final class CustomerTicketQueryBaseline {
    private static final int PAGE_SIZE = TicketPerformanceDataset.PAGE_SIZE;
    private static final String TENANT = TicketPerformanceDataset.TENANT;
    private static final int WARMUPS = 10;
    private static final int SAMPLES = 50;

    /** 传入一个新的输出目录；原始样本与自有库清理结果均保留。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output directory");
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("Output must be a new directory");
        try (var data = TicketPerformanceDataset.create(out)) {
            TenantContext.set(TENANT);
            try (var csv = new PrintWriter(Files.newBufferedWriter(out.resolve("ticket-query-samples.csv"), StandardCharsets.UTF_8))) {
                csv.println("scenario,iteration,page,total,rows,elapsedMs,firstId,lastId,error");
                var store = data.tickets();
                int rows = TicketPerformanceDataset.OWN_ROWS;
                measure(csv, store, "first-page", null, null, 1, rows);
                measure(csv, store, "last-page", null, null, rows / PAGE_SIZE, rows);
                measure(csv, store, "status-filter", TicketStatus.PROCESSING, null, 1, rows / 2);
                measure(csv, store, "assignee-filter", TicketStatus.PROCESSING, "agent-2", 1, rows / 10);
                measure(csv, store, "empty-filter", null, "agent-absent", 1, 0);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private static TicketQuery query(TicketStatus status, String assignee, int page) {
        return new TicketQuery(status, assignee, null, null, null, page, PAGE_SIZE);
    }

    private static void measure(PrintWriter csv, MybatisTicketStore store, String scenario,
                                TicketStatus status, String assignee, int page, int total) {
        TicketQuery query = query(status, assignee, page);
        for (int iteration = -WARMUPS; iteration < SAMPLES; iteration++) {
            long start = System.nanoTime();
            try {
                PageResult<Ticket> result = store.findPage(query);
                double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
                verify(result.total() == total, "Unexpected total");
                verify(result.items().size() == Math.min(total, PAGE_SIZE), "Unexpected page size");
                verify(result.items().stream().allMatch(t -> t.getId().startsWith(TENANT + "-")), "Foreign tenant record");
                if (iteration >= 0) {
                    String first = result.items().isEmpty() ? "" : result.items().get(0).getId();
                    String last = result.items().isEmpty() ? "" : result.items().get(result.items().size() - 1).getId();
                    csv.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%.6f,%s,%s,%n", scenario, iteration, page,
                        result.total(), result.items().size(), elapsedMs, first, last);
                    csv.flush();
                }
            } catch (RuntimeException failure) {
                csv.printf(Locale.ROOT, "%s,%d,%d,-1,-1,%.6f,,,%s%n", scenario, iteration, page,
                    (System.nanoTime() - start) / 1_000_000.0, failure.getClass().getSimpleName());
                csv.flush();
                throw failure;
            }
        }
    }

    private static void verify(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
