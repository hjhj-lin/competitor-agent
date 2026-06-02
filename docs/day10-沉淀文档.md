# Day 10 沉淀文档：Pipeline 编排 + 重试 + 降级

---

## 一、今天做了哪些功能

从"一个 Agent 单打独斗"升级为"四个 Agent 串行协作"：

```
用户创建任务 → Pipeline 串行编排
  → CollectAgent（采集）  重试2次，失败不跳过
  → AnalyzeAgent（分析）  重试2次，失败不跳过
  → ReportAgent（报告）   重试1次，失败不跳过
  → ReviewAgent（审核）   重试1次，失败跳过（降级）
→ 返回"报告 + 审核意见"
```

新增 7 个文件，修改 3 个文件，修复 2 个 Bug。

---

## 二、关键代码逻辑链路

### 2.1 完整请求链路

```
POST /api/tasks {companyName: "广东金融学院"}
  → TaskController.createTask()
    → AnalysisTaskService.createTask()
      → insert analysis_task (status=RUNNING)
      → AgentPipeline.execute(context)
        → CollectAgent → context.outputs["collect"]
        → AnalyzeAgent → 读 outputs["collect"] → context.outputs["analyze"]
        → ReportAgent  → 读 outputs["analyze"] → context.outputs["report"]
        → ReviewAgent  → 读 outputs["report"]  → context.outputs["review"]
      → 拼最终结果：报告 + 审核意见 + 降级提示
      → UPDATE analysis_task (status=COMPLETED)
```

### 2.2 Agent 间数据传递机制

`AgentContext.outputs` 是 `ConcurrentHashMap`，充当数据总线：

```
CollectAgent 执行完：
  context.outputs.put("collect", AgentResult{data: {collectResult: "..."}})

AnalyzeAgent 执行时：
  context.outputs.get("collect") → AgentResult
  agentResult.getData().get("collectResult") → String
```

### 2.3 Pipeline 三路分支

```java
if (!result.isSuccess()) {
    if (step.isSkipOnFailure()) {
        // 第1路：降级 —— 记录 degradeInfo，继续执行
    } else {
        // 第2路：中断 —— 返回 PipelineResult.fail()
    }
} else {
    // 第3路：成功 —— 放进 outputs，继续下一个
}
```

### 2.4 重试逻辑

`executeWithRetry()` 的循环：`maxRetries=2` 时执行 3 次（1次正常 + 2次重试）。
两种失败都触发重试：`AgentResult.fail()` 和 `catch Exception`。

---

## 三、重要概念的人话解释

| 术语 | 人话 |
|------|------|
| Pipeline | 项目经理，按顺序派活给四个专家，谁挂了决定是换人重试还是跳过 |
| AgentStep | 一张派工单：干啥活、最多重试几次、挂了能不能跳过 |
| PipelineResult | 项目总结报告：全成功 / 某人挂了项目黄了 / 某人挂了但跳过了 |
| 降级(degraded) | 审核专家请假了，报告照样出，只是标注"未经审核" |
| context.outputs | 四个专家共享的公告栏，每个人干完活把结果贴上去，下一个人来取 |
| maxRetries | 给专家几次机会，2 = 最多再试2次（总共3次） |
| skipOnFailure | 专家是不是可替代的，true = 这活不干也行 |

---

## 四、联调中踩过的坑

### 坑1：Pipeline steps 为空，IndexOutOfBoundsException

**现象：** 创建任务返回 `"Pipeline执行异常: Index -1 out of bounds for length 0"`

**原因：** `AgentPipeline` 同时标了 `@Component` 和 `@Bean`。Spring 创建了两个实例：
- `@Component` 创建的：steps 为空（没人调 `add()`）
- `@Bean` 创建的：steps 有 4 个 Agent

Service 注入的是空的那个。

**修复：** 去掉 `@Component`，只用 `@Bean` 配置。

**教训：** 需要构造时传参的 Bean，不要标 `@Component`。`@Component` 只能调无参构造器。

### 坑2：中文公司名传到后端变成问号

**现象：** "广东金融学院" 传到后端变成 "??????"，AI 拿到问号搜不到任何信息。

**原因：** PowerShell 的 `Invoke-RestMethod` 默认不用 UTF-8 编码发送请求体。

**修复：**
1. `application.yml` 加 `server.servlet.encoding.charset=UTF-8, force=true`
2. HTML 页面 `Content-Type` 加 `charset=utf-8`
3. PowerShell 测试时用 `[System.Text.Encoding]::UTF8.GetBytes()` 编码请求体

**教训：** 中文编码问题要从三端排查：发送端（浏览器/PowerShell）→ 服务端（Spring Boot）→ 存储端（MySQL）。本项目 MySQL 的 `characterEncoding=utf-8` 早就配了，问题出在发送端。

---

## 五、值得记住的经验

### 5.1 Pipeline 不需要 DAG

4 个 Agent 之间有严格依赖（采集→分析→报告→审核），不存在并行和条件分支。DAG 是在解一个不存在的问题。串行 Pipeline 60 行代码搞定，DAG 至少 300 行。

**面试话术：** "我设计了 Pipeline 编排，支持重试和降级。没有过度设计成 DAG，因为 4 个 Agent 之间有严格依赖，串行 Pipeline 就够了。"

### 5.2 降级不是失败

`PipelineResult.degraded()` 的 `success=true`。降级意味着"主要目标达成了，但质量打了折扣"。用户仍然能看到报告，只是标注"未经审核"。

### 5.3 每个 Agent 尽量自包含

AnalyzeAgent 的 `getCollectResult()` 有降级兜底：如果 collect 没执行或失败了，返回提示文字让 AI 自己搜索。这样即使上游数据缺失，Agent 也不会直接崩溃。

### 5.4 三层异常处理是 Agent 的标配

```
第1层：ReAct 循环正常结束 → COMPLETED
第2层：ReAct 循环结束但不成功 → FAILED（记录原因）
第3层：ReAct 循环抛异常 → FAILED（兜底）
```

每一层都写 `agent_execution` 记录，确保不管哪层出问题，数据库里都有迹可查。

### 5.5 ReActExecutor 支持自定义 Prompt 的方法

改 `execute(String question)` → `execute(String systemPrompt, String question)`，保留旧方法做兼容（调 `execute(DEFAULT_SYSTEM_PROMPT, question)`）。这样旧代码不受影响，新 Agent 可以传自己的 Prompt。

---

## 六、还不熟、下次还要追问的问题

1. **重试间隔：** 当前重试是立即重试，没有等待。AI API 限流（429）时应该加指数退避（1s→2s→4s），但当前 `executeWithRetry()` 里没做。Day 11 加异步任务时需要考虑。

2. **Agent 执行超时：** 项目总规划里写了"单 Agent 总执行超时 5 分钟"，但当前没有实现。ReActExecutor 只有 `MAX_ITERATIONS=10` 限制轮次，没有时间限制。如果一个 Agent 跑了 10 分钟还没结束，Pipeline 会一直等。

3. **成本控制：** 项目总规划里写了"单任务 AI 调用上限 20 次"，但当前没有实现。如果四个 Agent 各自跑满 10 轮 ReAct，总调用次数可能达到 40 次。需要在 Pipeline 或 ReActExecutor 层加全局限制。

4. **Pipeline 事务：** 当前 `createTask()` 没有 `@Transactional`。如果 Pipeline 执行到一半服务崩溃，`analysis_task` 的状态是 RUNNING 但永远不会更新。Day 11 加异步任务时需要处理。

5. **extractFromPipeline 的 agentName 参数没用到：** 当前按 `resultKey` 遍历所有 AgentResult 取值，`agentName` 参数是预留的。如果未来两个 Agent 的 data 里有同名 key（比如都有 "result"），就需要用 agentName 过滤。

---

## 七、新增/修改文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| ReActExecutor.java | 修改 | `execute(question)` → `execute(systemPrompt, question)` |
| CollectAgent.java | 修改 | 新增 `COLLECT_SYSTEM_PROMPT`，传入自定义 Prompt |
| AgentStep.java | 新增 | Pipeline 步骤：Agent + maxRetries + skipOnFailure |
| AgentPipeline.java | 新增 | 核心编排：串行执行 + 重试 + 降级 |
| PipelineResult.java | 新增 | 三态结果：success / fail / degraded |
| PipelineConfig.java | 新增 | @Bean 配置，组装四 Agent Pipeline |
| AnalyzeAgent.java | 新增 | 分析 Agent：提炼关键指标，对比竞品优劣势 |
| ReportAgent.java | 新增 | 报告 Agent：生成结构化竞品分析报告 |
| ReviewAgent.java | 新增 | 审核 Agent：交叉校验，标注置信度 |
| AnalysisTaskService.java | 修改 | 用 Pipeline 替换直接调 CollectAgent |
| application.yml | 修改 | 加 UTF-8 编码强制配置 |
