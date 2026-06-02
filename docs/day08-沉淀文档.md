# Day 8 沉淀文档 — ReAct 循环 + Steps 落库

## 一、今天做了什么

| 步骤 | 内容 | 验证结果 |
|------|------|----------|
| 8-1 | AgentContext 加 history 字段（List\<Message\>） | 编译通过 |
| 8-2 | 写 ReActExecutor + ReActStep + ReActResult | 编译通过 |
| 8-3 | CollectAgent 改用 ReActExecutor | 端到端验证：aiCallCount=7 |
| 8-4 | AgentExecution 加 steps 字段（JSON） | 数据库可查到4轮思考过程 |
| 8-5 | 全链路验证 + 代码精读 + 沉淀文档 | ✅ |

## 二、核心概念：ReAct 模式

### 人话解释
ReAct = Reasoning（推理）+ Acting（行动）。AI 不是一次性给出答案，而是：
1. 想一想我需要什么信息 → Thought
2. 决定去做什么 → Action
3. 看到结果 → Observation
4. 重复直到信息足够 → Final Answer

就像人做调研：先查公司简介 → 再查竞品 → 再查最新动态 → 信息够了 → 写报告。

### 技术实现
- System Prompt 告诉 AI 按固定格式输出（Thought/Action/Action Input）
- 正则表达式解析 AI 回复，提取三个关键字段
- 如果 Action=finish → 循环结束，返回 Final Answer
- 否则 → 执行对应工具 → 把 Observation 喂回对话历史 → 下一轮
- MAX_ITERATIONS=10 防死循环

## 三、完整请求链路

```
POST /api/tasks {companyName:"阿里巴巴"}
  → TaskController.createTask()
    → AnalysisTaskService.createTask()
      → 1. INSERT analysis_task (status=RUNNING)
      → 2. CollectAgent.execute(AgentContext)
        → ReActExecutor.execute("请采集阿里巴巴的竞品信息...")
          → 第1轮: AI → Thought+Action(search_company_info) → 执行工具 → Observation
          → 第2轮: AI → Thought+Action(search_competitors) → 执行工具 → Observation
          → 第3轮: AI → Thought+Action(search_latest_news) → 执行工具 → Observation
          → 第4轮: AI → Thought+Action(finish) → 返回 FinalAnswer
        → 返回 ReActResult(steps=4, aiCallCount=7)
      → 3. INSERT agent_execution (steps=JSON, aiCallCount=7)
      → 4. UPDATE analysis_task (status=COMPLETED, aiCallCount=7)
    → 返回 AnalysisTaskVO
```

## 四、关键代码职责

| 文件 | 职责 | 一句话总结 |
|------|------|-----------|
| ReActExecutor | 执行 ReAct 循环 | "AI 边想边做的控制器" |
| ReActStep | 一轮思考的快照 | "每一步的 Thought/Action/Observation" |
| ReActResult | 循环执行结果 | "成绩单：最终答案+过程+AI调用次数" |
| AgentContext.history | 对话历史 | "Agent 的记事本" |
| AiService.chatWithMessages | 多轮对话 | "把整个聊天记录发给 AI" |
| CollectAgent | 采集业务逻辑 | "委托 ReActExecutor 跑循环" |

## 五、新增/修改的文件清单

### 新增
1. `framework/ReActStep.java` — 一轮思考记录
2. `framework/ReActResult.java` — 循环执行结果
3. `framework/ReActExecutor.java` — ReAct 循环核心

### 修改
1. `framework/AgentContext.java` — 加 history 字段 + addUserMessage()
2. `service/AiService.java` — 加 chatWithMessages() 多轮对话方法
3. `framework/CollectAgent.java` — 注入 ReActExecutor，替换 aiService.chat()
4. `entity/AgentExecution.java` — 加 steps 字段（String，存 JSON）
5. `service/AnalysisTaskService.java` — aiCallCount 从硬编码1改为从 AgentResult 取

### 数据库
1. `agent_execution` 表加 `steps JSON` 列

## 六、踩坑记录

### 1. aiCallCount 硬编码为 1
- 问题：AnalysisTaskService 里 `task.setAiCallCount(1)` 是写死的
- 原因：Day 7 时 Agent 只调一次 AI，所以硬编码没问题
- 修复：从 `agentResult.getData().get("aiCallCount")` 取实际值
- 教训：**当底层逻辑从"一次调用"变成"多次调用"时，所有依赖它的上层代码都要检查**

### 2. DEEPSEEK_API_KEY 环境变量丢失
- 问题：重启终端后环境变量不在了
- 原因：之前只在当前 session 设了 `$env:DEEPSEEK_API_KEY`，没有持久化
- 修复：`[System.Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "sk-xxx", "User")` 设到用户级
- 教训：**敏感信息用系统级环境变量，不要写在代码里，也不要只设 session 级**

### 3. 数据库 competitor_agent 不存在
- 问题：启动报 `Unknown database 'competitor_agent'`
- 原因：换了一台机器/重装了 MySQL，数据库没了
- 修复：重新 CREATE DATABASE + 建表 + 注册用户
- 教训：**应该准备一个 init.sql 脚本，一键建库建表**

## 七、验证数据

### 阿里巴巴分析任务
- 状态：COMPLETED
- AI 调用次数：7
- ReAct 轮次：4
- steps 落库内容（节选）：
  - 第1轮：Thought=先收集基本信息 → Action=search_company_info → Observation=1999年成立...
  - 第2轮：Thought=已获取简介，搜索竞品 → Action=search_competitors → Observation=亚马逊、腾讯、京东...
  - 第3轮：Thought=竞品已获取，搜索最新动态 → Action=search_latest_news → Observation=Qwen3、3800亿投资...
  - 第4轮：Thought=所有信息已收集完毕 → Action=finish → FinalAnswer=完整分析报告

## 八、今日疑问清单

1. **ReAct 的"工具"目前是模拟的**（让 AI 根据知识库生成搜索结果），将来接真实搜索 API 时，ReActExecutor 的 executeAction() 怎么改造？
   - 思路：定义一个 Tool 接口，每个工具有 execute() 方法，ReActExecutor 通过 action 名称查找对应 Tool 执行

2. **AI 有时不按 ReAct 格式输出**（比如直接给答案，不写 Thought/Action），parseResponse() 会怎么处理？
   - 当前处理：thought 显示"(无法解析Thought)"，action 显示"unknown"
   - 改进方向：可以在 parseResponse 里检测到格式不对时，自动补一句"请按 Thought/Action/Action Input 格式回复"

3. **max-tokens=500 够吗？** ReAct 模式下 AI 要输出 Thought + Action + Action Input，如果 Final Answer 很长，500 tokens 可能截断
   - 当前观察：7次AI调用都成功了，说明 DeepSeek 在 500 tokens 内能完成
   - 改进方向：可以设 max-tokens=1000 或 2000，让 AI 有更多空间

4. **ObjectMapper 是 Spring Boot 自动创建的 Bean**，CollectAgent 直接注入没问题。但如果将来需要自定义序列化配置（比如日期格式），应该在哪里统一配置？
   - 思路：创建一个 @Configuration 类，自定义 ObjectMapper Bean

5. **ReAct 循环的 AI 调用次数怎么算？** 当前 aiCallCount=7，但实际 ReAct 只跑了4轮
   - 原因：每轮调1次 AI（ReAct 思考），加上每轮 executeAction 里又调1次 AI（模拟工具），所以是 4(思考) + 3(工具，最后一轮 finish 不调) = 7
   - 这个计数是准确的，因为每次调 AI 都消耗 token，都要计入成本
