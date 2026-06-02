# Day 11 沉淀文档：SSE 流式响应 + 异步任务

---

## 一、今天做了哪些功能

从"同步阻塞 40-60 秒"升级为"提交即返回 + SSE 实时推送进度"：

```
之前：POST /api/tasks → 等 60 秒 → 返回完整结果
现在：POST /api/tasks → 141ms 返回 taskId
      GET /api/tasks/{id}/stream → SSE 实时看 Agent 进度
      GET /api/tasks/{id} → 轮询查 currentAgent
```

同时修复了两个重要 Bug：
- `max-tokens: 500` 导致报告截断 → 改为 4096
- 四个 Agent 的 Prompt 缺少"不要使用训练数据编造"的约束

---

## 二、关键代码逻辑链路

### 2.1 异步执行链路

```
POST /api/tasks
  → TaskController.createTask()
    → AnalysisTaskService.createTask()
      → INSERT analysis_task (status=RUNNING)
      → pipelineExecutionService.executePipeline()  ← Spring AOP 代理拦截
      → 立即返回 {id, status: RUNNING}

pipeline-1 线程：
  → PipelineExecutionService.executePipeline()
    → SELECT analysis_task WHERE id=?
    → AgentPipeline.execute(context)
      → onAgentStart → UPDATE current_agent + SSE推送
      → CollectAgent → onAgentComplete → SSE推送
      → AnalyzeAgent → ...
      → ReportAgent → ...
      → ReviewAgent → ...
      → onPipelineComplete → SSE推送最终结果
    → UPDATE analysis_task (status=COMPLETED, result=...)
    → SseEmitter.complete()
```

### 2.2 SSE 事件流

```
event:agent
data:{"agent":"collect","status":"RUNNING"}

event:agent
data:{"agent":"collect","status":"COMPLETED"}

event:agent
data:{"agent":"analyze","status":"RUNNING"}

... (每个 Agent 的开始和完成)

event:result
data:{"status":"COMPLETED","aiCallCount":20}
```

### 2.3 JWT 双通道鉴权

```
普通 API：Authorization: Bearer xxx（Header）
SSE API：?token=xxx（Query 参数，因为 EventSource 不支持自定义 Header）
```

---

## 三、重要概念的人话解释

| 术语 | 人话 |
|------|------|
| @Async | 让方法在另一个线程里跑，调用者不用等它跑完 |
| pipelineExecutor | 专门跑 Pipeline 的线程池，2个常驻线程，最多5个 |
| CallerRunsPolicy | 线程池满了怎么办？让提交任务的人自己跑（降级为同步） |
| SseEmitter | Spring MVC 提供的 SSE 管道，往里 send() 前端就能收到 |
| ConcurrentHashMap | 线程安全的 HashMap，HTTP 线程和 pipeline 线程同时操作不会出问题 |
| PipelineListener | 观察者模式——Pipeline 干活时通知你，但不管你怎么处理通知 |
| EventSource | 浏览器原生 SSE API，只能 GET 请求，不能加自定义 Header |
| max-tokens | AI 单次回复的最大 token 数，500 ≈ 250 汉字，4096 ≈ 2000 汉字 |

---

## 四、联调中踩过的坑

### 坑1：@Async 在同类调用不生效

**现象：** `createTask()` 调 `this.executePipelineAsync()`，请求仍然阻塞 60 秒。

**原因：** Spring 的 `@Async` 基于 AOP 代理。`this.method()` 绕过代理，直接调原始对象的方法，`@Async` 注解等于白加。

**修复：** 把 `@Async` 方法抽到独立的 `PipelineExecutionService` 类。`AnalysisTaskService` 注入的是 Spring 代理对象，调 `pipelineExecutionService.executePipeline()` 时走代理。

**教训：** Spring 的 @Transactional、@Async、@Cacheable 都有同样的问题——同类内部调用不走代理。这是 Spring AOP 的基本限制，不是 Bug。

### 坑2：max-tokens: 500 导致报告截断

**现象：** 竞品分析报告写到一半就断了，表格只有表头没有数据。

**原因：** `application-dev.yml` 里 `max-tokens: 500`，AI 每次回复最多 500 token（约 250 汉字）。ReportAgent 的 finish 动作需要输出整个报告，500 token 远远不够。

**修复：** 改为 `max-tokens: 4096`。

**教训：** Day 8-9 测试时没发现，因为 ReAct 循环里每轮 AI 回复只有 Thought + Action + Action Input（几行），500 token 够用。但 finish 动作需要输出完整结论，token 需求暴增。**配置要按最大需求设，不是按平均需求。**

### 坑3：EventSource 不支持自定义 Header

**现象：** 前端用 `new EventSource(url)` 连接 SSE 端点，无法传 `Authorization: Bearer xxx` Header，JWT 拦截器返回 401。

**原因：** 浏览器的 EventSource API 不支持自定义 Header，这是 W3C 规范的限制。

**修复：** JwtInterceptor 支持从 `?token=xxx` query 参数读取 token。

**教训：** SSE/WebSocket 这类长连接 API，鉴权方式要提前考虑。Header 不行就用 query 参数，生产环境用短期一次性 token。

### 坑4：updateById 会把其他字段设为 null

**现象：** 用 `analysisTaskMapper.updateById(task)` 只更新 `currentAgent` 字段，结果其他字段被设为 null。

**原因：** MyBatis-Plus 的 `updateById` 默认策略是 `NOT_NULL` 不更新（null 字段不更新），但 `task` 对象只有 id 和 currentAgent 有值，其他字段为 null，根据策略不会更新。但某些配置下可能覆盖。

**修复：** 改用 `LambdaUpdateWrapper` 只更新 `current_agent` 字段：
```java
wrapper.eq(AnalysisTask::getId, taskId)
       .set(AnalysisTask::getCurrentAgent, agentName);
analysisTaskMapper.update(null, wrapper);
```

**教训：** 只更新一个字段时，用 `UpdateWrapper` 比 `updateById` 更安全。

### 坑5：AI 用训练数据编造事实

**现象：** 搜索结果说"比亚迪成立于1994年"，但报告写"成立于1995年"；搜索结果没有 2026 年数据，但报告写"2025年数据"。

**原因：** AI 把搜索结果和自己的训练数据混在一起，训练数据截止到 2024 年，所以编造了过时信息。

**修复：** 四个 Agent 的 Prompt 都加了"所有信息必须来自搜索结果，不要使用训练数据编造"和"搜索时加上年份"。

**教训：** Prompt 约束只能减轻幻觉，不能根除。真正可靠的方式是搜索结果直接注入报告，不让 AI "自由发挥"。这是后续优化方向。

---

## 五、值得记住的经验

### 5.1 异步方法必须放在独立的 Spring Bean 里

这是 Spring AOP 的铁律。`@Async`、`@Transactional`、`@Cacheable` 都一样——同类内部调用不走代理。记住：**你想让 Spring 帮你做的事（事务、异步、缓存），都必须通过代理对象调用。**

### 5.2 SseEmitter 比 Flux<ServerSentEvent> 更适合 Spring MVC

虽然用户说"用 Flux"，但实际实现用了 SseEmitter，原因：
- 项目是 Spring MVC（不是 WebFlux）
- Pipeline 在 @Async 线程里执行，用 `emitter.send()` 直接推送更自然
- Flux 更适合纯响应式场景（数据源本身就是 Flux）

如果未来整个项目切到 WebFlux，再换成 Flux<ServerSentEvent>。

### 5.3 观察者模式解耦 Pipeline 和通知逻辑

PipelineListener 接口让 Pipeline 不关心"进度怎么通知"。当前用 SSE + 数据库更新，未来换 WebSocket 或消息队列，只改 Listener 实现，不改 Pipeline 代码。**开闭原则。**

### 5.4 max-tokens 配置要按最大需求设

ReAct 循环里每轮回复只需要几十 token，但 finish 动作可能需要几千 token。配置要按最大需求设，不是按平均需求。

### 5.5 ConcurrentHashMap 是跨线程共享状态的标准选择

HTTP 线程创建 SseEmitter，pipeline 线程发送事件。两个线程同时操作 emitters Map，ConcurrentHashMap 保证线程安全。

---

## 六、还不熟、下次还要追问的问题

1. **SSE 连接断开重连：** 当前前端 EventSource 断开后会自动重连，但重连时 SseEmitter 已经被移除了。需要实现"重连后补发历史事件"的机制，或者让前端重连后通过轮询 GET /api/tasks/{id} 获取当前状态。

2. **线程池监控：** 当前没有监控 pipelineExecutor 的活跃线程数、队列长度。如果多个用户同时提交任务，可能触发 CallerRunsPolicy 降级为同步。需要加 Micrometer 指标或 Actuator 端点。

3. **任务超时处理：** 如果 Pipeline 执行超过 5 分钟（SseEmitter 超时），任务状态仍然是 RUNNING，永远不会变成 COMPLETED 或 FAILED。需要加一个定时任务扫描超时的 RUNNING 任务。

4. **AI 幻觉问题：** Prompt 约束"不要使用训练数据"只是软约束，AI 仍然可能编造。更可靠的方案是：搜索结果直接作为结构化数据注入报告模板，不让 AI 自由发挥。这需要改 ReAct 循环的设计。

5. **SSE 的 token 安全：** query 参数传 token 会留在 URL 日志和浏览器历史里。生产环境应该用短期一次性 token（专门为 SSE 生成的、5 分钟过期的 token）。

6. **PipelineExecutionService 的降级提示拼接：** 当前 `task.setResult()` 被调了两次（第 54 行和第 59 行），如果中间出异常可能存了不带降级提示的版本。应该统一在所有拼接完成后才调一次 `setResult()`。

---

## 七、新增/修改文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| AsyncConfig.java | 新增 | @EnableAsync + 线程池配置 |
| PipelineExecutionService.java | 新增 | @Async 异步执行 Pipeline |
| SseEmitterService.java | 新增 | 管理 SseEmitter，推送 SSE 事件 |
| PipelineListener.java | 新增 | Pipeline 事件监听接口 |
| AnalysisTaskService.java | 修改 | 拆成同步建记录 + 调异步执行 |
| AiService.java | 修改 | 新增 chatStream() 流式方法 |
| AgentPipeline.java | 修改 | 加 listener() + 回调通知 |
| PipelineConfig.java | 修改 | 注入 SseEmitterService + Listener + 更新 currentAgent |
| TaskController.java | 修改 | 新增 SSE 端点 GET /{id}/stream |
| JwtInterceptor.java | 修改 | 支持 query 参数传 token |
| CollectAgent.java | 修改 | Prompt 加"不要使用训练数据"和"加年份搜索" |
| AnalyzeAgent.java | 修改 | Prompt 加"不要使用训练数据" |
| ReportAgent.java | 修改 | Prompt 加"不要截断"和"不要使用训练数据" |
| ReviewAgent.java | 修改 | Prompt 加"基于搜索结果判断" |
| application-dev.yml | 修改 | max-tokens: 500 → 4096 |
| pom.xml | 修改 | 加 spring-boot-starter-webflux |
