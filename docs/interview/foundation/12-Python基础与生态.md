# 基础补全第 12 期 · Python 基础与生态

> **本期结论**：不少 Java Agent 岗位写着“熟悉 Python 优先”。原因是模型、评测、数据处理的生态主要在 Python，
> Agent 团队也常常需要维护一部分 Python 服务或脚本。本期覆盖 Python 的高频基础题，以及 LangChain、LangGraph、FastAPI 的实操要点，
> 并对照 Java 说明差异，便于 Java 开发者快速建立对应关系。
>
> 说明：本期的示例代码已在 Python 3.11 下运行验证，使用的库版本为 pydantic 2.13、langchain-core 1.6、langgraph 1.2、
> fastapi 0.141、pytest 9.1。LangChain / LangGraph 迭代很快，其他版本请以官方文档为准。
>
> 题量 12。

---

### Q1 什么是 GIL？它对 Python 的并发有什么影响？

**考察点**：Python 并发的基础限制。

**参考回答**

- **GIL（全局解释器锁）**：CPython 中同一时刻只允许一个线程执行 Python 字节码。
- **影响**：
  - **CPU 密集型任务**：多线程无法利用多核，需要使用多进程（`multiprocessing`）或者用 C 扩展实现（例如 NumPy 在计算时会释放 GIL）；
  - **IO 密集型任务**：线程在等待 IO 时会释放 GIL，多线程或 asyncio 仍然有效。
- **Agent 服务的特点**：主要是等待模型和工具的 IO，使用 asyncio 就能获得很好的并发能力。
- **新进展**：新版本的 CPython 提供了可以关闭 GIL 的实验性构建，但生态的兼容性仍在完善中。
- **与 Java 对比**：Java 的线程可以真正并行执行，没有 GIL 的限制。

**追问**：*Python 服务中大量计算向量相似度，怎么提升性能？* 使用 NumPy 做向量化计算，或者交给向量数据库处理，而不是用 Python 循环逐个计算。

**减分回答**：认为“Python 不支持多线程”。

---

### Q2 asyncio 的原理是什么？async/await 怎么使用？

**考察点**：Python 的异步编程。

**参考回答**

- **原理**：单线程的事件循环调度多个协程；协程执行到 `await` 一个 IO 操作时让出控制权，事件循环转而执行其他就绪的协程，IO 完成后再恢复执行。
- **基本用法**：

```python
import asyncio


async def call_tool(name: str, delay: float) -> str:
    await asyncio.sleep(delay)  # 模拟 IO 调用
    return f"{name} done"


async def main() -> None:
    # 并发执行多个工具调用，并为整体设置超时
    results = await asyncio.wait_for(
        asyncio.gather(call_tool("order", 0.1), call_tool("logistics", 0.2), return_exceptions=True),
        timeout=5,
    )
    print(results)


asyncio.run(main())
```

- **常见的坑**：
  1. 在协程中调用阻塞的函数（例如同步的 HTTP 客户端、`time.sleep`），会阻塞整个事件循环，需要改用异步库，或者用 `asyncio.to_thread` 放到线程中执行；
  2. 忘记 `await`，协程根本不会执行；
  3. `gather` 默认遇到异常就传播，可以用 `return_exceptions=True` 隔离单个任务的失败。
- **与 Java 对比**：类似于 Reactor 的事件循环模型，“不能阻塞事件循环”的原则也完全一样。

**追问**：*怎么限制同时进行的模型调用数量？* 使用 `asyncio.Semaphore`，在每次调用前 `async with semaphore:`。

**减分回答**：在 `async` 函数中使用 `requests` 这类同步库而不自知。

---

### Q3 Python 的装饰器是什么？在 Agent 开发中有什么应用？

**考察点**：Python 的语言特性。

**参考回答**

- **定义**：装饰器是一个接收函数、返回新函数的函数，用来在不修改原函数的情况下增加功能。
- **示例**：为工具调用增加耗时统计和异常兜底。

```python
import functools
import time


def tool_guard(func):
    @functools.wraps(func)  # 保留原函数的名称和文档，工具描述常常依赖它们
    def wrapper(*args, **kwargs):
        start = time.perf_counter()
        try:
            return func(*args, **kwargs)
        except Exception as error:  # 把可读的错误返回给模型，而不是让整轮对话失败
            return f"工具执行失败：{error}"
        finally:
            print(f"{func.__name__} took {time.perf_counter() - start:.3f}s")

    return wrapper


@tool_guard
def query_order(order_id: str) -> str:
    """查询订单状态。"""
    return f"订单 {order_id} 已发货"
```

- **在 Agent 框架中的应用**：LangChain 的 `@tool` 装饰器会根据函数签名和文档字符串生成工具的 schema。
- **与 Java 对比**：功能上类似于 Java 的注解加 AOP，或者装饰器模式。

**追问**：*为什么要使用 `functools.wraps`？* 否则包装后的函数会丢失原函数的名称和文档字符串，依赖这些信息生成工具描述的框架会得到错误的结果。

**减分回答**：说不出装饰器的本质是高阶函数。

---

### Q4 生成器和 yield 是什么？如何用它实现流式输出？

**考察点**：惰性计算与流式处理。

**参考回答**

- **生成器**：包含 `yield` 的函数，调用时返回一个生成器对象；每次迭代执行到 `yield` 时产出一个值并暂停，下一次迭代从暂停的位置继续。
- **优点**：惰性计算，不需要一次性把所有数据放进内存，非常适合流式输出。
- **异步生成器**：`async def` 中使用 `yield`，用 `async for` 迭代，适合流式地转发模型的输出。

```python
from collections.abc import AsyncIterator


async def stream_reply(chunks: AsyncIterator[str]) -> AsyncIterator[str]:
    buffer = []
    async for chunk in chunks:
        buffer.append(chunk)
        yield chunk  # 逐片转发给前端
    # 流正常结束后，才把完整答案写入缓存或数据库
    full_answer = "".join(buffer)
    print(f"answer length={len(full_answer)}")
```

- **与 Java 对比**：类似于 Java 的 `Iterator` 或者 Reactor 的 `Flux`。

**追问**：*生成器在中途出错时，写入缓存的逻辑还会执行吗？* 上面的写法中，出错时异常会从 `async for` 抛出，后面的代码不会执行，
这正是期望的行为：半截的回答不应该被缓存。

**减分回答**：把所有输出拼接成完整的字符串后再返回，失去了流式的意义。

---

### Q5 Python 的类型提示和 Pydantic 在 Agent 开发中有什么作用？

**考察点**：结构化数据与校验。

**参考回答**

- **类型提示**：为变量和函数标注类型，提升可读性，配合 mypy 等工具做静态检查；运行时不会强制校验。
- **Pydantic**：基于类型提示定义数据模型，在运行时校验和转换数据，可以导出 JSON Schema。
- **在 Agent 开发中的作用**：
  1. 定义工具的参数，并自动生成工具的 schema；
  2. 定义结构化输出的格式，并校验模型的输出；
  3. 定义接口的请求和响应（FastAPI 直接使用 Pydantic 模型）。

```python
from typing import Literal

from pydantic import BaseModel, Field


class IntentResult(BaseModel):
    intent: Literal["presale", "consult", "order", "refund", "complaint", "other"]
    order_id: str | None = Field(default=None, description="订单号，没有则为空")
    need_human: bool = False


raw = '{"intent": "refund", "order_id": "20260613001"}'
result = IntentResult.model_validate_json(raw)  # 字段非法时抛出 ValidationError
```

- **与 Java 对比**：类似于 Java 的 record 加 Bean Validation，再加上 JSON Schema 的生成工具。

**追问**：*模型输出的 JSON 校验失败了怎么办？* 把校验错误的信息反馈给模型重试一次，仍然失败时返回兜底的结果（例如 `other`）。

**减分回答**：直接用 `json.loads` 解析后按字典的键取值，不做任何校验。

---

### Q6 Python 中有哪些常见的“坑”？

**考察点**：语言细节。

**参考回答**

- **可变默认参数**：`def f(items=[])` 中的默认列表只在函数定义时创建一次，多次调用会共享同一个列表。应该使用 `None` 作为默认值，在函数内部创建。
- **浅拷贝与深拷贝**：`copy.copy` 只复制外层，嵌套的对象仍然共享；修改对话历史这类嵌套结构时要注意。
- **闭包的延迟绑定**：在循环中创建的 lambda 引用的是循环变量本身，而不是当时的值。
- **`is` 与 `==`**：`is` 比较是否是同一个对象，`==` 比较值是否相等；判断 `None` 用 `is None`。
- **异常被吞掉**：过于宽泛的 `except:` 会捕获包括 `KeyboardInterrupt` 在内的所有异常，要捕获具体的异常类型。

```python
def add_message(message: str, history: list[str] | None = None) -> list[str]:
    if history is None:  # 不要写成 history=[]
        history = []
    history.append(message)
    return history
```

**追问**：*可变默认参数在 Agent 服务中可能导致什么问题？* 不同用户的对话历史被存进同一个共享的列表中，导致数据串用。

**减分回答**：不知道可变默认参数的问题。

---

### Q7 Python 项目的依赖和环境怎么管理？

**考察点**：工程化实践。

**参考回答**

- **虚拟环境**：每个项目使用独立的环境，避免依赖冲突（`venv`、conda 等）。
- **依赖管理工具**：pip 加 `requirements.txt`；Poetry；uv（速度快，近年来使用越来越广泛）。
- **锁定版本**：使用锁文件固定所有依赖（包括间接依赖）的确切版本，保证不同环境的一致性。
  LangChain 这类迭代很快的库尤其需要锁定版本。
- **代码质量工具**：Ruff（代码检查和格式化）、mypy（类型检查）、pytest（测试）。
- **部署**：打包成容器镜像；基础镜像和依赖版本都要固定。
- **与 Java 对比**：类似于 Maven 或 Gradle 的依赖管理和版本锁定。

**追问**：*升级 LangChain 的版本后，程序行为发生了变化，怎么避免？* 锁定版本；升级时阅读变更说明；用评测集和测试做回归验证。
这和 Java 中框架升级的注意事项相同（见[进阶第 01 期 Q11](../advanced/01-Agent运行时与Harness深入.md)）。

**减分回答**：直接用系统的 Python 全局安装依赖。

---

### Q8 LangChain 的核心概念有哪些？

**考察点**：主流 Python 框架的认知。

**参考回答**

- **模型接口**：统一的聊天模型接口，屏蔽不同厂商的差异。
- **提示词模板**：带变量的提示词，例如 `ChatPromptTemplate`。
- **输出解析器**：把模型的输出解析成字符串或结构化的对象。
- **LCEL（表达式语言）**：用 `|` 把提示词、模型、解析器组合成一条链，统一支持同步、异步、流式和批量调用。
- **工具**：用 `@tool` 装饰器定义工具，通过 `bind_tools` 绑定到模型。
- **检索器与向量存储**：用于 RAG。

```python
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.tools import tool


@tool
def query_order(order_id: str) -> str:
    """根据订单号查询订单状态。"""
    return f"订单 {order_id} 已发货"


prompt = ChatPromptTemplate.from_messages([
    ("system", "你是电商客服，回答要简洁。"),
    ("human", "{question}"),
])


def build_chain(model):
    # model 为任意聊天模型实例，例如某个厂商的 ChatModel
    return prompt | model | StrOutputParser()
```

- **与 Java 生态的对应**：Spring AI 的 ChatClient 和 Advisor、LangChain4j 的 AI Services 提供了类似的能力。

**追问**：*LangChain 常被批评的问题有哪些？* 抽象层次多、版本迭代快、调试时不容易看清实际发给模型的内容；生产项目需要锁定版本并做好观测。

**减分回答**：只知道 LangChain 能“调用大模型”。

---

### Q9 用 LangGraph 实现一个带工具调用和人工审批的 Agent，核心代码怎么写？

**考察点**：图编排框架的实操。

**参考回答**（示意代码，API 以所用版本的文档为准）

```python
from typing import Annotated, TypedDict

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages


class State(TypedDict):
    # add_messages 定义了合并规则：新消息追加到列表中，而不是覆盖
    messages: Annotated[list, add_messages]


def build_graph(call_model, run_tools, needs_tool):
    builder = StateGraph(State)
    builder.add_node("agent", call_model)  # 调用模型，可能产生工具调用
    builder.add_node("tools", run_tools)   # 执行工具，可在其中对高风险工具请求人工确认
    builder.add_edge(START, "agent")
    # 条件边：有工具调用就去执行工具，否则结束
    builder.add_conditional_edges("agent", needs_tool, {"tools": "tools", "end": END})
    builder.add_edge("tools", "agent")
    # 检查点：按 thread_id 保存状态，支持多轮对话、中断和恢复；生产环境应使用持久化的存储
    return builder.compile(checkpointer=MemorySaver())


# 调用时通过 thread_id 区分会话：
# graph.invoke({"messages": [("user", "我要退款")]}, config={"configurable": {"thread_id": "session-1"}})
```

- **人工审批**：在需要审批的节点中触发中断，流程暂停并保存状态；拿到人工的决定后，用同一个 `thread_id` 恢复执行
  （框架提供了中断和恢复的机制，具体 API 以所用版本为准）。
- **概念对应**：状态、节点、条件边、检查点，见[基础补全第 03 期](03-工作流编排与图框架.md)。

**追问**：*为什么示例中的 `MemorySaver` 不能直接用于生产环境？* 状态只保存在进程内存中，服务重启就会丢失，多实例之间也无法共享；
生产环境需要使用基于数据库或 Redis 的检查点存储。

**减分回答**：只会运行官方的示例，说不出状态合并规则和检查点的作用。

---

### Q10 用 FastAPI 实现一个 SSE 流式对话接口。

**考察点**：Python Web 服务的实操。

**参考回答**

```python
import json
from collections.abc import AsyncIterator

from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

app = FastAPI()


class ChatRequest(BaseModel):
    session_id: str
    message: str


async def generate_reply(message: str) -> AsyncIterator[str]:
    # 实际项目中这里逐片读取模型的流式输出
    for piece in ["您好，", "正在为您查询", "订单状态。"]:
        yield piece


@app.post("/chat/stream")
async def chat_stream(request: ChatRequest) -> StreamingResponse:
    async def event_stream() -> AsyncIterator[str]:
        async for piece in generate_reply(request.message):
            yield f"data: {json.dumps({'delta': piece}, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},  # 关闭代理缓冲
    )
```

- **要点**：每个事件以空行结束；关闭代理缓冲；客户端断开时停止生成（可以检测请求是否已经断开）；设置超时和心跳。
- **与 Java 对比**：相当于 Spring WebFlux 中返回 `Flux<ServerSentEvent>`。

**追问**：*为什么数据用 JSON 包装，而不是直接输出文本？* 文本中可能包含换行符，会破坏 SSE 的格式；
用 JSON 包装还能携带事件类型、消息 ID 等附加信息。

**减分回答**：使用同步的生成器，并在其中调用阻塞的 HTTP 客户端。

---

### Q11 Java 服务和 Python 服务如何协作？

**考察点**：多语言架构。

**参考回答**

- **常见的分工**：Java 负责业务系统集成、权限、工单、配置管理这类业务逻辑；Python 负责模型推理、评测、数据处理、部分 Agent 编排。
- **通信方式**：
  1. **HTTP / REST**：最简单通用，流式输出可以使用 SSE；
  2. **gRPC**：强类型的接口定义，性能好，支持双向流；
  3. **消息队列**：异步任务（文档处理、批量评测）；
  4. **MCP**：把 Python 实现的能力封装成 MCP 服务，供 Java 的 Agent 调用。
- **需要统一的地方**：身份和租户上下文的传递；链路追踪的传递（统一使用 OpenTelemetry）；错误码；超时和重试策略。
- **原则**：尽量减少跨语言调用的次数和层级；接口要稳定、有版本；避免两边重复实现同一个业务逻辑。

**追问**：*两边都需要计算 token 成本，怎么保证口径一致？* 只在一个地方计量（例如统一的模型网关），另一边读取计量结果，而不是各自计算。

**减分回答**：两边各自实现一遍相同的业务逻辑。

---

### Q12 Python 中如何编写 Agent 相关代码的测试？

**考察点**：Python 测试实践。

**参考回答**

- **框架**：pytest；异步代码使用 pytest-asyncio 这类插件。
- **隔离外部依赖**：用 mock 或者假的模型实现替代真实的模型调用，测试编排逻辑、工具的分支、错误处理；
  可以录制真实的模型响应作为测试数据，回放测试。
- **参数化测试**：用 `pytest.mark.parametrize` 覆盖多种输入。
- **评测与测试分开**：确定性的逻辑用单元测试；模型效果用评测集，在 CI 中定期运行或者在发布前运行。
- **与 Java 对比**：类似于 JUnit 加 Mockito；同样要警惕“mock 掩盖真实的装配问题”，关键链路需要集成测试。

```python
import pytest


def route(intent: str) -> str:
    return {"order": "order_expert", "refund": "after_sales_expert"}.get(intent, "all_experts")


@pytest.mark.parametrize(
    ("intent", "expected"),
    [("order", "order_expert"), ("refund", "after_sales_expert"), ("unknown", "all_experts")],
)
def test_route(intent: str, expected: str) -> None:
    assert route(intent) == expected
```

**追问**：*怎么测试流式输出的逻辑？* 用假的异步生成器模拟模型的输出（包括中途出错的情况），断言转发的分片和缓存的写入行为是否正确。

**减分回答**：“模型的输出不确定，所以没法写测试”。
