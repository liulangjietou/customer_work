# 第 06 期 · Java 并发与响应式：流式对话链路的工程细节

> **本期结论**：Java Agent 岗位的 JD 几乎都要求“并发和 JVM 基础扎实”。在流式 Agent 项目里，这项能力具体体现在
> 四个方面：线程切换、上下文传播、流的终止语义，以及测试是否真的能暴露问题。本期题目都来自项目里踩过的真实问题，
> 能把原理和现象对上的候选人不多。
>
> 对应 JD：Java 并发、Spring WebFlux / Reactor、Redis 分布式锁、单元测试能力。题量 7（初 1 / 中 4 / 高 2）。

---

### Q1【初级】Reactor 里 `subscribeOn` 和 `publishOn` 有什么区别？项目在哪些地方用了它们，为什么？

**考察点**：Reactor 的线程模型；不能阻塞 Netty 事件循环。

**参考回答**

- **区别**：
  - `subscribeOn` 决定**订阅从哪个线程开始**，影响的是数据源，通常用来把阻塞调用挪出去；
  - `publishOn` 决定**它下游的算子在哪个线程执行**。
- **项目中的 `subscribeOn(boundedElastic)`**：凡是阻塞调用都挪到弹性线程池执行，包括：
  - 查语义缓存时调用 Embedding（阻塞的 HTTP 请求）；
  - 获取会话锁；
  - 意图分类（`classifyIntent`）；
  - 受管知识库检索；
  - 多 Agent 编排中每个专家的调用。
- **项目中的 `publishOn(boundedElastic)`**：`streamFromAgent` 在 `streamEvents` 后面加了一个 `publishOn`。
  旧的 `stream()` API 在末尾自带这一步，`streamEvents` 没有。如果不切换线程，下游的敏感词过滤和 SSE 写出
  都会在模型的 IO 线程上执行，拖慢模型分片的读取。
- **反例**：在 `reactor-http-nio` 线程上调用 `block()`，Reactor 会检测到并抛出 `IllegalStateException`。
  意图分类就踩过这个坑，见第 02 期 Q8。

**追问**：*`boundedElastic` 线程池满了会怎样？*
Reactor 的默认上限是 CPU 核数 × 10 个线程，每个线程的任务队列上限是 10 万个；超出后提交会被拒绝。
所以阻塞调用必须有超时，比如工具执行超时 15 秒、专家调用超时 60 秒，否则线程会被慢请求长期占住。

**减分回答**：“两个都是切换线程，效果差不多”。

---

### Q2【中级】ThreadLocal 在响应式链路里为什么会丢失？项目是怎么把租户上下文传到 MyBatis 拦截器里的？

**考察点**：ThreadLocal 和 Reactor Context 各自的适用场景；上下文自动传播机制；这套机制的性能代价。

**参考回答**

- **为什么租户上下文要放在 ThreadLocal 里**：MyBatis 拦截器是同步 API，拿不到 Reactor Context。
  可行的方向只有一个：把 Reactor Context 中的值同步到 ThreadLocal。
- **具体机制**：
  1. WebFilter 把租户写入 Reactor Context，键是 `customer-work.tenant`。
  2. `TenantContextThreadLocalAccessor` 是 Micrometer context-propagation 的 `ThreadLocalAccessor`，
     负责在 Reactor Context 和 ThreadLocal 之间搬运这个值。
  3. 开启 `Hooks.enableAutomaticContextPropagation()` 后，链路每次切换线程时，Reactor 都会自动把 ThreadLocal 恢复出来。
- **三个坑**：
  1. **自动传播绑定在 `customer-work.tenant.enabled` 开关上**：单租户部署时这个开关关闭，依赖自动传播的功能会静默失效。
  2. **`Mono.block()` 会开启一次新的订阅，这时 Reactor Context 是空的**。引用回传功能就栽在这里（见第 03 期 Q3）。
  3. **性能问题**：开启自动传播后，Reactor 会给链上的**每个算子**都包一层，每层在 `onNext` 时都要恢复一遍所有 ThreadLocal。
     AI 流式链路实测有 110 多层，模型每吐出一个增量，恢复方法就要被调用上百次。
     原来恢复时每次都跑一遍正则校验租户格式，结果把一整个 CPU 核跑满，流式吞吐被压到大约每秒 5 个字符。
     修复方式是：恢复方法 `restore()` 跳过校验，只保留归一化；格式校验只放在入口处的 `set()` 里做一次。
- **显式冻结**：需要异步调度时，不能依赖自动传播，要先 `TenantContext.get()` 取出租户，再在目标线程上 `callWith`。
  `ManagedKnowledge.retrieve` 就是这样做的。

**追问**：*`CrossTenantOperations` 为什么要自己维护一个嵌套深度计数？*
MyBatis-Plus 的 `clearIgnoreStrategy()` 会无条件清除忽略策略。内层作用域退出时会把外层的豁免一起清掉，
外层剩下的语句就会意外恢复租户过滤。自己记录嵌套深度，只在最外层进入和退出时切换状态，嵌套调用才安全。

**减分回答**：“用 `InheritableThreadLocal` 就能解决”。线程池里的线程是复用的，它解决不了这个问题。

---

### Q3【中级】热 Agent 缓存用 `LinkedHashMap` 实现了 LRU。这个实现有哪些细节？有没有潜在的性能问题？

**考察点**：集合框架的基本功；同步容器的锁粒度；资源释放。

**参考回答**

- **实现**：`new LinkedHashMap<>(256, 0.75f, true)`，第三个参数 `accessOrder=true` 表示按访问顺序排列；
  再重写 `removeEldestEntry`，超过 1000 个时淘汰最久未访问的一项，避免缓存无限增长导致 OOM。
  外面再包一层 `Collections.synchronizedMap` 保证线程安全。
- **淘汰时释放资源**：`AgentResourceCloser.closeQuietly` 负责释放。因为 `ReActAgent#close()` 是空实现，
  Toolkit（其中可能有 MCP 客户端）必须显式关闭。
- **遍历时要手动加锁**：`flushHotAgents` 在 `synchronized (sessionAgents)` 块里先复制出所有值再清空；
  同步容器的迭代器本身不是线程安全的。
- **潜在问题**（适合用来追问）：`resolveAgent` 调用的是 `synchronizedMap.computeIfAbsent`。同步容器的这个方法
  会在**整个计算期间持有全局锁**，所以不同会话构建 Agent（装配工具、加载 Skill、连接 MCP）的过程会被串行化；
  `removeEldestEntry` 里的关闭操作也是在锁内执行的。

**追问**：*如果并发建会话成为瓶颈，你会怎么改？*
可以换成按 key 加锁的容器（比如基于 `ConcurrentHashMap` 的缓存实现，或者 Caffeine，它们的 compute 只锁单个 key），
也可以在锁外构建 Agent，再用 `putIfAbsent` 放入缓存，竞争失败的一方关闭自己多建的那个实例。
但要先用压测数据证明这里确实是瓶颈，再决定是否修改。

**减分回答**：只会说“LinkedHashMap 可以实现 LRU”，讲不出锁粒度和资源释放。

---

### Q4【高级】流式对话的错误处理：`doOnComplete` 和 `doFinally`、`switchIfEmpty` 和 `defaultIfEmpty` 分别该怎么选？

**考察点**：Reactor 的终止信号语义；“下游必须恰好被调用一次”这个不变量。

**参考回答**

- **写语义缓存用 `doOnComplete`，不用 `doFinally`**：`doFinally` 在出错和取消时也会执行。
  一条中途失败的流累积下来的是“半截回答 + 兜底文案”，把它缓存起来，之后每个问到同类问题的人都会收到这段残缺的回复。
  除此之外还有一个 `degraded` 标志：`streamFromAgent` 走兜底逻辑时会把它置位，调用方据此决定不写缓存。
- **中间件的判定链路必须恰好产出一个元素**（Jev 中间件的教训）：
  - `Mono.fromCallable` 返回 null 时会变成空流，下游永远不会被调用，这一轮对话就没有回复。
  - 用 `switchIfEmpty(next)` 兜底也不行：下游本身返回空事件流时，它同样会被触发，结果 Agent 被执行了两遍。
  - 正确的写法是 `map(Optional::of).defaultIfEmpty(Optional.empty())`，然后在 `flatMapMany` 里用
    `concatWith(Flux.defer(() -> next.apply(input)))` 调用下游。
  - `JevMiddlewareRobustnessTest` 针对 Jev 的每一种表现都断言下游**恰好被调用一次**。
- **流式超时的语义**：`flux.timeout(Duration)` 限制的是**相邻两个元素之间**的间隔，也就是空闲超时，
  默认 `stream.idle-timeout-seconds=120`，用来缓解框架 #1741 的连接泄漏问题。超时后用 `onErrorResume` 返回兜底文案，并标记为降级。
- **释放锁的逻辑挂在内层**：`withSessionLock` 只在拿到锁之后才注册 `doFinally` 释放锁，
  避免加锁失败时把别人持有的锁释放掉。

**追问**：*这里的 `Flux.defer` 起什么作用？*
保证每次订阅都拿到一份新的状态，比如 `deltaSeen` 标志和敏感词 guard。重新订阅时这些状态必须从头开始，
否则会把上一次订阅残留的状态串到这一次。

**减分回答**：“出错了 `onErrorResume` 返回一个默认值就行”，没有考虑已经发出去的内容和缓存副作用。

---

### Q5【中级】会话锁为什么用 Redisson 的 `RPermitExpirableSemaphore`，而不用 `RLock`？

**考察点**：分布式锁和线程的绑定关系；租约时长；锁服务故障时怎么降级。

**参考回答**（`RedissonSessionLock`）

- **原因**：`RLock` 是和线程绑定的可重入锁，解锁时会校验当前线程是不是持有者。项目在 Reactor 链路里加锁、
  在 `doFinally` 里释放，加锁和释放不保证在同一个线程上，用 `RLock` 会在释放时抛出 `IllegalMonitorStateException`。
  `PermitExpirableSemaphore` 加锁时返回一个 permitId，**释放时只认这个 id，不认线程**。
  设置 permits=1 就能实现互斥，`trySetPermits(1)` 本身是幂等的。
- **租约是硬保险**：实例崩溃时锁会自动过期，不会永久卡住会话。租约必须明显长于单轮对话的最长处理时间，
  否则长回复处理到一半就失去了互斥保护。默认等待 10 秒，租约 120 秒。
- **降级**：Redis 不可用时降级为进程内锁（只打一次错误日志）。对强一致要求高的入口，可以关闭降级（`fallbackOnError=false`）。
- **释放失败不向上抛异常**：释放发生在 `doFinally` 里，抛出去只会掩盖真正的业务异常；何况租约到期后锁也会自动释放。

**追问**：*进程内的锁对象怎么回收？*
`InMemorySessionLock` 用 `ConcurrentHashMap.compute` 维护每个会话锁的使用者计数，最后一个使用者释放时移除这一项。
“计数归零”和“移除”在同一次原子操作里完成，不会和正在进来的请求发生竞争。
早期版本每遇到一个新会话就放进一个 Semaphore，而且从不移除，长期运行会导致内存泄漏。

**减分回答**：“用 `SETNX` 加过期时间就行”，没有考虑释放时的身份校验和租约长度。

---

### Q6【中级】单元测试里有哪些陷阱会导致断言“恒为真”？请举项目里的例子。

**考察点**：测试有没有效；能不能用变异测试检验断言。

**参考回答**

- **`CompletableFuture` 回调跑在调用线程上**：如果 future 在挂上 `thenApply` 时**已经完成**，回调会同步执行在调用线程上。
  这时上下文还在，断言必然通过；即使去掉 `TenantContext.callWith` 做变异测试，测试也不会失败
  （`McpContractServiceTest` 踩过这个坑）。正确做法是让 future 在被测方法**返回之后**、由另一个线程完成。
- **单测里没有挂拦截器，断言“没抛异常”必然成立**：应该断言在查询发生的那一刻
  `InterceptorIgnoreHelper.willIgnoreTenantLine(...)` 为真，并且退出作用域之后为假。
- **`assertNotNull` 没有检验能力**：框架的 Builder 自带一份默认的 `OllamaOptions`，参数没有传进去时它照样不是 null，
  只是里面的值全是 null。直到变异测试报出的是 NPE，而不是预期的断言提示，才发现这条断言从来没有生效过。
- **加了重载方法后，Mockito 的 stub 静默失效**：已有的测试 stub 的是旧签名，代码调用的是新签名，mock 直接返回 null，
  NPE 出现在离改动很远的地方。
- **在 `when(...)` 的参数里调用会 stub 其他 mock 的辅助方法**：Mockito 会报 `UnfinishedStubbingException`，
  但错误信息指向外层那一行，容易让人以为是外层写错了。

**追问**：*怎么确认一条断言是有效的？*
做变异测试：故意把被测逻辑改错，确认测试会失败，并且失败信息就是自己写的那条提示。

**减分回答**：“覆盖率 80% 就说明测试没问题”。

---

### Q7【高级】两个“编译能通过，但能力被悄悄丢掉”的坑：接口的 default 方法遇上装饰器，继承遇上防御性拷贝。

**考察点**：Java 语言机制的细节；组合与继承带来的隐性风险。

**参考回答**

- **装饰器没有转发 default 方法**：AgentScope 2.0.3 给 `AgentStateStore` 增加了 `supportsVersioning`、
  `getVersioned`、`saveIfVersion` 三个方法，都带有 default 实现。`SandboxSafeAgentStateStore` 不转发这几个方法也能编译通过，
  但 `supportsVersioning()` 会一直返回 `false`，版本化写入就这样被悄悄关掉了。
  规则是：**接口每增加一个 default 方法，所有装饰器就多欠一处转发**。框架升级后要 grep 一遍
  `implements <框架接口>`，逐个对照新接口的方法列表检查。
- **继承遇上防御性拷贝**：`DefaultActiveGroupsToolkit` 重写了 `setActiveGroups`（空集合视为“尚未初始化”，而不是“全部清空”）
  和 `getToolSchemas`。但 `ReActAgent.Builder#build()` 会调用 `toolkit.copy()` 做防御性拷贝，
  拷贝出来的是基类 `Toolkit`，两处重写就全部失效了（实测修复无效）。
  解决方法是重写 `copy()` 直接返回 `this`：Toolkit 是按会话新建的，交给 Builder 之后原引用就不再使用，
  不存在共享问题。这个前提写在了注释里；如果将来要复用同一个 Toolkit 实例，需要换一种方案。
- **共同教训**：“组合优于继承”是对的，但组合同样要保证转发完整。能力是否还在，要用**真实的被装饰对象**验证语义是否穿过了包装层，
  比如 `SandboxSafeAgentStateStoreVersioningTest`。

**追问**：*能否用工具自动防止漏转发？*
可以写一个基于反射的测试：枚举接口的全部方法，断言装饰器类自己声明了这些方法（用 `getDeclaredMethod` 检查）。
这类结构断言便宜，而且能在框架升级当天就失败。

**减分回答**：不知道 default 方法会让“忘记转发”在编译期不报任何错误。
