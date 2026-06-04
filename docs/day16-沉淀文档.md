# Day 16 沉淀文档：用Spring AI Tool Calling替代自研ReAct框架

---

## 一、今天做了哪些功能

**核心改造：用Spring AI封装好的Tool Calling机制，替代自研的ReAct循环引擎**

| 操作 | 文件 | 行数变化 |
|------|------|---------|
| 新增 | `tool/SearchTools.java` | +20行 |
| 改造 | `framework/CollectAgent.java` | 125行→97行 |
| 改造 | `framework/AnalyzeAgent.java` | 137行→109行 |
| 改造 | `framework/ReportAgent.java` | 143行→114行 |
| 改造 | `framework/ReviewAgent.java` | 145行→116行 |
| 删除 | `framework/ReActExecutor.java` | -130行 |
| 删除 | `framework/ReActStep.java` | -17行 |
| 删除 | `framework/ReActResult.java` | -36行 |
| 删除 | `service/AiService.java` | -57行 |
| **净减** | — | **约270行** |

**验证结果**：登录→创建任务→4个Agent串行执行→COMPLETED→报告11090字→搜索正常

---

## 二、关键代码逻辑链路

### 2.1 改造后的Agent执行链路

```
CollectAgent.execute()
  → chatClient.prompt()          // 创建请求
      .system(COLLECT_SYSTEM_PROMPT)  // 角色指令
      .user(question)                  // 具体任务
      .tools(searchTools)              // 注册@Tool声明的搜索工具
      .call()                          // 发起调用，框架自动循环
      .content()                       // 获取最终文本

Spring AI框架内部自动完成:
  1. 发消息给DeepSeek（含tools参数，描述searchWeb工具）
  2. DeepSeek返回tool_calls: {name:"searchWeb", arguments:{query:"华为公司简介"}}
  3. 框架自动调用SearchTools.searchWeb("华为公司简介")
  4. 框架将搜索结果作为tool消息追加回对话
  5. 再次调用DeepSeek（含搜索结果上下文）
  6. DeepSeek决定是否继续调用工具或直接输出
  7. 循环直到DeepSeek返回纯文本 → .content()拿到结果
```

### 2.2 四层职责分工

```
SpringAiConfig  —  工厂层：创建ChatClient Bean（配置模型+重试）
       ↓ 注入
Agent类         —  业务层：构建问题、取上游数据、记录执行、返回结果
       ↓ .tools(searchTools) 注册
SearchTools     —  声明层：@Tool告诉LLM"你有什么工具可用"
       ↓ 调用
TavilyService   —  实现层：实际执行搜索，Tavily优先+DeepSeek降级
```

### 2.3 Agent间数据传递（不变）

```
context.outputs["collect"]  → AnalyzeAgent取 collectResult
context.outputs["analyze"]  → ReportAgent取 analysisResult
context.outputs["report"]   → ReviewAgent取 reportResult
```

这部分是业务逻辑，Spring AI无法替代。

---

## 三、重要概念的人话解释

| 术语 | 人话 |
|------|------|
| Tool Calling | LLM的"动手能力"。LLM自己决定什么时候调用工具、传什么参数，框架自动执行并把结果喂回去 |
| @Tool注解 | 声明式工具注册。写一个方法加@Tool，Spring AI自动把方法名、参数、描述转成JSON Schema告诉LLM |
| .tools() vs .toolCallbacks() | `.tools(new XxxTools())`传@Tool注解的对象；`.toolCallbacks(callback)`传编程式创建的ToolCallback。前者简单，后者灵活 |
| function calling协议 | LLM原生支持的协议。LLM不输出"Action: search_web"这种文本，而是返回结构化JSON `{name:"searchWeb", arguments:{query:"xxx"}}`，框架自动解析 |
| ChatClient | Spring AI的对话客户端。封装了发消息、注册工具、自动循环调用工具、获取结果的全部逻辑 |
| Prompt简化 | 旧Prompt要教LLM输出特定格式（Thought/Action/Action Input），因为我们要正则解析。新代码用function calling协议，LLM原生支持，不需要教格式 |

---

## 四、联调中踩过的坑

### 坑1：.toolCallbacks() vs .tools() 方法搞混

**现象**：编译报错 `SearchTools无法转换为ToolCallback`

**原因**：Spring AI 1.1.3 中：
- `.toolCallbacks()` 接受 `ToolCallback` 或 `ToolCallbackProvider` 类型
- `.tools()` 接受 `@Tool` 注解的普通对象

**解决**：把 `.toolCallbacks(searchTools)` 改为 `.tools(searchTools)`

**教训**：Spring AI的API命名容易混淆，`tools()`是声明式（@Tool），`toolCallbacks()`是编程式（手动构建ToolCallback）。用@Tool注解就用`.tools()`。

### 坑2：删除自研文件后编译报错

**现象**：删除ReActExecutor等4个文件后，4个Agent还引用它们

**原因**：先删了文件，但Agent代码还没改完

**解决**：先改造4个Agent（删除对ReActExecutor的依赖），再删除自研文件。顺序很重要：**先改造消费者，再删除生产者**。

---

## 五、值得记住的经验

### 5.1 "用框架替代自研"的正确姿势

```
1. 先搞清框架能替代什么、不能替代什么
   能替代: ReAct循环、Action解析、工具执行、Observation追加
   不能替代: Agent间数据传递、业务编排（Pipeline）、执行记录

2. 先新增框架代码，再改造消费者，最后删除旧代码
   新增: SearchTools.java
   改造: 4个Agent
   删除: ReActExecutor/ReActStep/ReActResult/AiService

3. 每一步都编译验证，不要攒到最后
```

### 5.2 Spring AI Tool Calling vs 自研ReAct的本质区别

| 维度 | 自研ReAct | Spring AI Tool Calling |
|------|-----------|----------------------|
| LLM输出 | 自由文本 "Action: search_web" | 结构化JSON tool_calls |
| 解析方式 | 正则表达式（脆弱） | 框架自动解析（可靠） |
| 工具注册 | 硬编码if-else | @Tool注解声明 |
| 循环控制 | 手写for循环 | 框架自动循环 |
| 扩展工具 | 改executeAction代码 | 加@Tool方法 |
| Prompt | 需要教格式（浪费token） | 只写业务指令 |

### 5.3 代码精读要抓分层

不要泛泛看"改了哪些文件"，而是盯住每一层在做什么：
- **工厂层**（SpringAiConfig）：创建ChatClient，配置模型参数
- **业务层**（Agent类）：构建问题、取上游数据、记录执行
- **声明层**（SearchTools）：@Tool告诉LLM可用工具
- **实现层**（TavilyService）：实际执行搜索，处理降级

每一层只做一件事，改一层不影响其他层。

---

## 六、还不熟、下次还要追问的问题

1. **Spring AI Tool Calling的最大循环次数是多少？** 自研ReAct设了MAX_ITERATIONS=10，Spring AI框架默认限制是多少？能配置吗？如果LLM一直调用工具不停止怎么办？

2. **.tools()传入的对象是每次new还是可以复用？** 当前4个Agent都注入同一个SearchTools Bean，Spring AI内部是否线程安全？

3. **Tool Calling的流式版本怎么用？** 当前用`.call()`是同步阻塞，如果要用`.stream()`流式输出，工具调用的循环怎么处理？

4. **多工具场景下@Tool的description怎么写？** 当前只有1个工具，description写什么都行。如果有5个工具，description的写法会直接影响LLM选择哪个工具，有什么最佳实践？

5. **TavilyService每次new HttpClient的问题** — 这是已知问题，应该在什么时候修？是现在修还是等后续优化模块统一处理？

6. **agent_execution表的steps字段现在为空** — 旧代码会序列化ReActStep列表，新代码没有步骤记录了。需要补充吗？Spring AI有办法获取工具调用的中间过程吗？
