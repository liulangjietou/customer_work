import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.ticket.client.CustomerWorkTicketClient;
import com.richard.fyoung.customeradmin.ticket.config.CurrentAgentResolver;
import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientConfig;
import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientProperties;
import com.richard.fyoung.customeradmin.ticket.controller.UserTicketController;
import com.richard.fyoung.customeradmin.ticket.service.UserTicketService;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.lock.InMemorySessionLock;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.chat.AgentMessageAcceptanceService;
import com.richard.fyoung.customerworkapp.controller.AgentTicketController;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 本机性能专用服务：生产 Admin MVC → RestClient 签名 → 客服 WebFlux → Service → MyBatis。
 * 登录态用固定测试坐席代替，不测登录、权限拦截器、WS 或模型推理；写请求在最外层全部拒绝。
 */
public final class CustomerServicePerformanceServer {
    private static final String LOOPBACK = "127.0.0.1";
    private static final String HEADER = "X-Performance-Token";
    private static final String AGENT = "agent-8";

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    public static class CustomerHttpConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    public static class AdminHttpConfiguration { }

    /** 传入一个新的证据目录；READY 后由标准输入 EOF 或进程退出触发正常清理。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output directory");
        Path output = Path.of(args[0]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Output must be a new directory");
        var stopped = new CountDownLatch(1);
        var cleaned = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            stopped.countDown();
            try {
                if (!cleaned.await(30, TimeUnit.SECONDS)) System.err.println("PERFORMANCE_CLEANUP_TIMEOUT");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "performance-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try (var data = TicketPerformanceDataset.create(output)) {
            run(data, output, stopped);
        } finally {
            cleaned.countDown();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        }
    }

    private static void run(TicketPerformanceDataset data, Path output, CountDownLatch stopped) throws Exception {
        String secret = UUID.randomUUID() + "-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        var properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(true);
        properties.getAgentAccess().setSecret(secret);
        var tickets = new TicketService(data.tickets(), (ticket, event) -> {
            throw new IllegalStateException("Writes are forbidden in the performance server");
        }, CustomerWorkTransactionExecutor.DIRECT);
        var replies = new AgentMessageAcceptanceService(tickets, data.messages(),
            new WsSessionRegistry(new ObjectMapper()), new InMemorySessionLock(5), CustomerWorkTransactionExecutor.DIRECT);
        var customer = new AnnotationConfigApplicationContext();
        var admin = new AnnotationConfigWebApplicationContext();
        var tomcat = new Tomcat();
        DisposableServer customerServer = null;
        boolean tomcatStarted = false;
        try {
            customer.register(CustomerHttpConfiguration.class);
            customer.registerBean("agentTickets", AgentTicketController.class,
                () -> new AgentTicketController(tickets, data.messages(), replies));
            customer.registerBean("agentAuth", AgentAuthWebFilter.class, () -> new AgentAuthWebFilter(properties));
            customer.registerBean("readOnly", WebFilter.class, () -> (exchange, chain) -> {
                if (exchange.getRequest().getMethod() != HttpMethod.GET) {
                    exchange.getResponse().setStatusCode(HttpStatus.METHOD_NOT_ALLOWED);
                    return exchange.getResponse().setComplete();
                }
                return chain.filter(exchange);
            });
            customer.refresh();
            customerServer = HttpServer.create().host(LOOPBACK).port(0)
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(customer).build()))
                .bindNow(Duration.ofSeconds(30));
            var clientProperties = new CustomerWorkClientProperties();
            clientProperties.setBaseUrl("http://" + LOOPBACK + ":" + customerServer.port());
            clientProperties.setAgentSecret(secret);
            // 此处只固定已登录身份；实际业务客户端仍逐请求签名，客服端仍验签并绑定租户。
            var identity = new CurrentAgentResolver() {
                @Override
                public String currentAgentId() { return AGENT; }
            };
            var client = new CustomerWorkTicketClient(new CustomerWorkClientConfig()
                .customerWorkRestClient(clientProperties, identity));
            tomcat.setBaseDir(output.resolve("tomcat").toString());
            tomcat.setHostname(LOOPBACK);
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("address", LOOPBACK);
            Path documentBase = Files.createDirectories(output.resolve("empty-web-root"));
            var context = tomcat.addContext("", documentBase.toString());
            admin.register(AdminHttpConfiguration.class);
            admin.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("userTickets",
                new UserTicketController(new UserTicketService(client, clientProperties), identity)));
            var wrapper = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(admin));
            wrapper.setLoadOnStartup(1);
            context.addServletMappingDecoded("/", "dispatcher");
            Filter guard = (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                var reply = (HttpServletResponse) response;
                if (!token.equals(http.getHeader(HEADER))) { reply.sendError(401); return; }
                if (!"GET".equals(http.getMethod())) { reply.sendError(405); return; }
                String path = http.getRequestURI();
                if (!path.matches("/api/ticket/(page|performance-owned-[0-9]+(?:/messages)?)")) {
                    reply.sendError(404);
                    return;
                }
                String prior = TenantContext.get();
                TenantContext.set(TicketPerformanceDataset.TENANT);
                long start = System.nanoTime();
                try {
                    chain.doFilter(request, response);
                } finally {
                    if (prior == null) TenantContext.clear(); else TenantContext.set(prior);
                    // 最终响应可能已经提交；详细时间由浏览器 ResourceTiming 与独立 SQL 样本记录。
                    synchronized (output) {
                        Files.writeString(output.resolve("http-requests.csv"), path + "," +
                            (http.getQueryString() == null ? "" : http.getQueryString()) + "," + reply.getStatus() + "," +
                            (System.nanoTime() - start) / 1_000_000.0 + "\n",
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                }
            };
            var filter = new FilterDef();
            filter.setFilterName("performanceScope");
            filter.setFilter(guard);
            context.addFilterDef(filter);
            var mapping = new FilterMap();
            mapping.setFilterName(filter.getFilterName());
            mapping.addURLPattern("/*");
            context.addFilterMap(mapping);
            tomcat.start();
            tomcatStarted = true;
            var settings = new Properties();
            settings.setProperty("adminBaseUrl", "http://" + LOOPBACK + ":" + tomcat.getConnector().getLocalPort());
            settings.setProperty("customerBaseUrl", clientProperties.getBaseUrl());
            settings.setProperty("requestHeader", HEADER);
            settings.setProperty("requestToken", token);
            settings.setProperty("tenant", TicketPerformanceDataset.TENANT);
            settings.setProperty("agent", AGENT);
            settings.setProperty("ownRows", Integer.toString(TicketPerformanceDataset.OWN_ROWS));
            settings.setProperty("otherRows", Integer.toString(TicketPerformanceDataset.OTHER_ROWS));
            settings.setProperty("messagesPerConversation", Integer.toString(TicketPerformanceDataset.MESSAGE_COUNT));
            try (var writer = Files.newBufferedWriter(output.resolve("server.properties"))) {
                settings.store(writer, "Local performance scope; login and websocket are excluded");
            }
            // 管理进程只在配置完整关闭后读取，避免读取刚创建但尚未写完的属性文件。
            Files.writeString(output.resolve("server.ready"), "ready\n");
            System.out.println("PERFORMANCE_SERVER_READY");
            System.out.flush();
            // 管理进程持有 stdin；其退出或发送任意一行都结束本次自有环境。
            Thread input = new Thread(() -> {
                try { System.in.read(); } catch (java.io.IOException ignored) { }
                stopped.countDown();
            }, "performance-input");
            input.setDaemon(true);
            input.start();
            stopped.await();
        } finally {
            try {
                if (tomcatStarted) tomcat.stop();
            } finally {
                try {
                    tomcat.destroy();
                } finally {
                    try {
                        admin.close();
                    } finally {
                        try {
                            if (customerServer != null) customerServer.disposeNow(Duration.ofSeconds(10));
                        } finally {
                            customer.close();
                        }
                    }
                }
            }
        }
    }
}
