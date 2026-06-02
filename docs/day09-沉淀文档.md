# Day 9 沉淀文档 — Tavily+DeepSeek 联网搜索 + Bug 修复

## 一、今天做了哪些功能

| 步骤 | 内容 | 验证结果 |
|------|------|----------|
| 9-1 | 测试 Tavily 连通性 + 配置 API Key | Java HttpClient 可连，PowerShell 超时 |
| 9-2 | 写 TavilyService（Tavily + DeepSeek 双层降级） | 编译通过 |
| 9-3 | ReActExecutor 接入 TavilyService | 端到端验证：Observation 含真实网页来源 |
| 9-4 | 更新 REACT_SYSTEM_PROMPT（search_web 统一工具） | AI 正确选择 search_web |
| 9-5 | 全面接口测试（28 个用例） | 发现 5 个 Bug |
| 9-6 | Bug 修复 + 回归测试 | 全部通过 |

## 二、关键代码逻辑链路

### 联网搜索完整链路

```
POST /api/tasks {companyName:"泡泡玛特"}
  → TaskController.createTask()
    → AnalysisTaskService.createTask()
      → CollectAgent.execute()
        → ReActExecutor.execute()
          → 第1轮: AI思考 → Action=search_web → tavilyService.search("泡泡玛特 公司简介")
            → Tavily API 返回 200 → parseTavilyResult() → "【泡泡玛特(HK9992) 公司资料_F10_同花顺】..."
          → 第2轮: AI思考 → Action=search_web → tavilyService.search("泡泡玛特 竞品")
            → Tavily API 返回 200 → "【泡泡玛特竞争对手分析 - 东方财富网】..."
          → 第3轮: AI思考 → Action=finish → 返回最终分析
        → ReActResult(steps=3, aiCallCount=3)
      → AgentExecution 落库（steps=JSON, aiCallCount=3）
    → analysis_task 更新（status=COMPLETED）
```

### 双层降级链路

```
tavilyService.search(query)
  ├─ 有 Tavily Key？
  │   ├─ 是 → searchWithTavily(query)
  │   │   ├─ HTTP 200 → parseTavilyResult() → 返回真实搜索结果
  │   │   ├─ HTTP 非200 → searchWithDeepSeek(query)  ← 降级1
  │   │   └─ 异常/超时 → searchWithDeepSeek(query)    ← 降级1
  │   └─ 否 → searchWithDeepSeek(query)               ← 直接走降级
  └─ searchWithDeepSeek(query)
      ├─ HTTP 200 → parseDeepSeekResponse() → 返回 DeepSeek 搜索结果
      ├─ HTTP 非200 → 返回 "搜索失败：statusCode"
      └─ 异常 → 返回 "搜索异常：errorMessage"
```

### 错误码修复链路

```
BusinessException.unauthorized("无权操作")  → code=401
  → GlobalExceptionHandler.handleBusinessException()
    → mapToHttpStatus(401) → HttpStatus.UNAUTHORIZED
    → ResponseEntity.status(401).body(Result.error(401, "无权操作"))
  → 前端收到 HTTP 401 + {code:401, message:"无权操作"}
```

## 三、重要概念的人话解释

### 1. Tavily vs DeepSeek 搜索

| | Tavily | DeepSeek V4 enable_search |
|---|--------|--------------------------|
| 人话 | 专门的搜索引擎 API | AI 自带的搜索能力 |
| 搜索次数 | 无限制（免费1000次/月） | 单会话只能搜1次 |
| 返回格式 | 结构化（answer + results 列表） | 自然语言文本 |
| 国内可用 | Java 能调通，PowerShell 超时 | 完全可用 |
| 适合场景 | 主力搜索引擎 | 降级兜底 |

### 2. 为什么 DeepSeek V4 的 enable_search 只能搜一次？

DeepSeek V4 的联网搜索是"单次触发"的——AI 在一次对话中只会主动搜索一次，后续就用自己的知识回答。这不是 Bug，是 DeepSeek 的设计：每次搜索都消耗额外资源，限制次数防止滥用。

所以 ReAct 循环里如果用 DeepSeek 搜索，第1轮能搜到真实信息，第2轮 AI 会说"我无法联网搜索"。而 Tavily 没有这个限制，每轮都能搜。

### 3. ResponseEntity vs 直接返回 Result

之前所有接口都返回 `Result<?>`，Spring MVC 自动包成 HTTP 200。前端拿到的永远是：
```
HTTP 200 OK
{code: 401, message: "无权操作"}  ← 业务错误但 HTTP 状态码是 200
```

改成 `ResponseEntity<Result<?>>` 后：
```
HTTP 401 Unauthorized
{code: 401, message: "无权操作"}  ← HTTP 状态码和业务码一致
```

为什么重要？因为前端框架（Axios）根据 HTTP 状态码决定走 success 还是 error 回调。HTTP 200 + code 401 会导致前端走 success 回调，然后手动检查 code，容易遗漏。

### 4. 静态工厂方法模式

```java
// 之前：要记住 401 代表什么
throw new BusinessException(401, "无权操作");

// 现在：一眼看懂
throw BusinessException.unauthorized("无权操作");
```

好处：可读性 + 约束合法业务码。新代码只能用 unauthorized/notFound/badRequest，不能随便写 new BusinessException(999, "...")。

## 四、联调中踩过的坑

### 1. PowerShell 调 Tavily 超时，但 Java 能调通

- 现象：`Invoke-RestMethod -Uri "https://api.tavily.com/search"` 30秒超时
- 原因：PowerShell 的 HTTP 客户端走系统代理，可能被干扰
- Java 的 HttpClient 默认不走系统代理，直连成功
- 教训：**终端测试超时不代表服务端也不行，要区分客户端网络问题和服务端问题**

### 2. Spring AI 不支持 DeepSeek 的 enable_search 参数

- 现象：在 application-dev.yml 里加 `enable-search: true`，编译报错
- 原因：Spring AI 1.1.3 的 OpenAI 兼容模式请求体是固定结构的，额外参数被忽略
- 解决：TavilyService 里直接用 Java HttpClient 调 DeepSeek API，手动传 `enable_search: true`
- 教训：**第三方框架的"兼容模式"不等于"完全兼容"，特殊参数可能不支持**

### 3. 全面测试发现了之前没注意的 Bug

- 越权删除：以为是 Bug，实际不是（所有任务都是 admin 创建的，用 admin token 删当然成功）
- 错误码：业务错误返回 500，前端无法区分"服务器崩了"和"密码错误"
- 分页：负数页码没保护
- 类型转换：`/api/tasks/abc` 返回 500 而不是 400
- 教训：**写代码时觉得"没问题"的地方，测试时往往能发现 Bug。测试用例要覆盖正常+异常+边界**

### 4. DeepSeek V4 enable_search 单会话只能搜一次

- 现象：第1轮搜索成功，第2轮 AI 说"我无法联网搜索"
- 原因：DeepSeek V4 的联网搜索是单次触发的
- 解决：Tavily 为主力（无限制），DeepSeek 为降级兜底
- 教训：**第三方 AI 的"联网搜索"能力有限制，不能假设它和专用搜索 API 一样**

## 五、值得记住的经验

### 1. 双层降级架构

```
主力（无限制）→ 降级（有限制）→ 返回错误
```

这个模式可以复用到任何外部 API 调用场景。比如：
- 短信发送：阿里云为主 → 腾讯云降级
- 支付：微信支付为主 → 支付宝降级
- 存储：阿里云 OSS 为主 → 本地存储降级

关键点：**降级是自动的，不需要改代码**。Tavily 失败自动走 DeepSeek，配置驱动。

### 2. 配置驱动 > 代码硬编码

```yaml
tavily:
  api-key: ${TAVILY_API_KEY:}   # 空值就走降级
  timeout-seconds: 10            # 可调整
```

不写 `if (env.equals("prod"))`，而是通过配置的有无来决定走哪条路。更干净。

### 3. 测试要覆盖三类场景

| 类型 | 例子 | 目的 |
|------|------|------|
| 正常场景 | 正确密码登录 | 验证功能可用 |
| 异常场景 | 错误密码、越权删除 | 验证错误处理 |
| 边界条件 | 负数页码、超长字符串、非数字ID | 验证鲁棒性 |

### 4. HTTP 状态码要正确

前端依赖 HTTP 状态码做分流。业务错误返回 200 是"反模式"，会让前端不得不在每个请求里检查 code 字段。

### 5. escapeJson 不够完整

当前只转义了 `\`、`"`、`\n`，缺少 `\r`、`\t`、Unicode 控制字符。当前够用（query 都是公司名），但将来如果用户输入不可控，应该换成 ObjectMapper。

## 六、还不熟、下次还要追问的问题

1. **Spring AI 的 Tool Calling 机制怎么用？**
   - 当前 ReAct 循环是手动解析 AI 回复的正则，Spring AI 1.1.3 有 @Tool 注解可以自动注册工具
   - 但 @Tool 需要配合 ChatClient 的 toolCallbacks 配置，还没搞清楚怎么和 ReAct 循环结合
   - Day 10 是否应该用 Spring AI 原生的 Tool Calling 替换手动正则解析？

2. **DeepSeek V4 的 enable_search 有没有官方文档说明限制？**
   - 实测单会话只能搜一次，但没找到官方文档确认
   - 如果将来 DeepSeek 更新了这个限制，代码需要同步调整吗？

3. **Tavily 的免费额度 1000 次/月，用完了怎么办？**
   - 当前每次分析任务大约消耗 3-4 次 Tavily 搜索
   - 1000 次大约能做 250-330 个分析任务
   - 用完后会自动降级到 DeepSeek 搜索，但 DeepSeek 有单会话限制
   - 是否需要加额度监控和告警？

4. **ObjectMapper 应该用 Spring Boot 自动注入的还是每次 new？**
   - TavilyService 里每次 `new ObjectMapper()` 解析 JSON
   - Spring Boot 已经自动配置了一个 ObjectMapper Bean
   - 但 TavilyService 不方便注入（因为 searchWithTavily/searchWithDeepSeek 是 private 方法）
   - 最佳实践是什么？

5. **ReAct 循环的 max-tokens=500 够吗？**
   - AI 需要输出 Thought + Action + Action Input，如果 Final Answer 很长可能被截断
   - 实测目前没遇到截断，但将来分析更复杂的公司时可能不够
   - 是否应该把 max-tokens 提高到 1000 或 2000？
