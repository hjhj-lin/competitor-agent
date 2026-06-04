# Day 15 沉淀文档：代码精读 + 知识点复盘 + 面试话术

---

## 一、今天做了哪些功能

没有新功能代码，专注**5条核心链路精读 + 面试话术整理**：

```
之前：代码能跑，但讲不清每条链路的完整流转
现在：5条主链路从头到尾讲得清，面试话术准备好了
```

| Step | 做了什么 | 精读文件数 |
|------|---------|-----------|
| 15-1 | 登录鉴权链路精读 | 7个文件 |
| 15-2 | 任务创建+Pipeline链路精读 | 10个文件 |
| 15-3 | ReAct执行+工具调用链路精读 | 10个文件 |
| 15-4 | 报告存储+成本控制链路精读 | 6个文件 |
| 15-5 | 前端闭环链路精读 | 1个文件（1933行） |
| 15-6 | 整理面试话术 | 综合5条链路 |

---

## 二、关键代码逻辑链路

### 2.1 登录鉴权链路

```
前端 POST /api/auth/login {username, password}
  → WebMvcConfig: excludePathPatterns 放行
  → AuthController.login()
    → SysUserService.login()
      → SELECT * FROM sys_user WHERE username=? AND deleted=0
      → BCryptPasswordEncoder.matches(明文, 哈希)
      → 失败统一返回"用户名或密码错误"（防枚举攻击）
      → JwtUtil.generateToken(userId, username)
        → Jwts.builder().subject(username).claim("userId",userId)
            .expiration(now+86400000).signWith(HMAC-SHA密钥)
      → 返回 {token, username}
  → 前端 localStorage.setItem("token", token)

每次请求:
  → JwtInterceptor.preHandle()
    → 取token: Authorization: Bearer xxx 或 ?token=xxx
    → JwtUtil.parseToken() → 验签名+过期
    → request.setAttribute("currentUserId", userId)
    → Controller通过 @RequestAttribute("currentUserId") 获取
```

**关键安全点**：
- `@JsonIgnore` 在 SysUser.password 上 → API不泄露哈希
- `@PostConstruct` 校验JWT密钥强度 → 防止默认密钥上线
- 登录失败不区分用户名/密码错误 → 防枚举

### 2.2 任务创建+Pipeline链路

```
POST /api/tasks {companyName:"华为"}
  → JwtInterceptor验token → currentUserId
  → AnalysisTaskService.createTask()
    → aiUsageService.checkDailyLimit() → SELECT ai_usage_daily
    → INSERT analysis_task (status=RUNNING)
    → pipelineExecutionService.executePipeline() → @Async异步

异步线程:
  → AgentContext.of(taskId, companyName)
  → agentPipeline.execute(context)
    → 4步串行: collect→analyze→report→review
    → 每步: listener.onAgentStart() → UPDATE current_agent + SSE推送
    → 每步: listener.onAgentComplete() → SSE推送
    → 重试: collect/analyze最多2次, report/review最多1次
    → 降级: review失败可跳过, 标注"结果未经审核"
  → 成功: 拼接报告+审核意见 → UPDATE task + INSERT report + addUsage
  → 失败: UPDATE task (status=FAILED)
  → sseEmitterService.completeEmitter() → 关闭SSE
```

### 2.3 ReAct执行+工具调用链路

```
ReActExecutor.execute(systemPrompt, userQuestion)
  → messages = [SystemMessage, UserMessage]
  → for i = 0..9:
      1. aiService.chatWithMessages(messages) → DeepSeek API
         → aiCallCount++
      2. parseResponse(aiResponse) → 正则提取 Thought/Action/ActionInput
      3. action == "finish"? → return ReActResult.success(actionInput)
      4. action == "search_web"? → tavilyService.search(actionInput)
         → Tavily优先, DeepSeek联网搜索兜底
      5. messages.add(UserMessage("Observation: " + result))
  → 超过10轮 → ReActResult.fail()

Agent间数据传递:
  context.outputs["collect"] → analyze读取 collectResult
  context.outputs["analyze"] → report读取 analysisResult
  context.outputs["report"]  → review读取 reportResult
```

### 2.4 报告存储+成本控制链路

```
报告写入:
  Pipeline成功 → extractFromPipeline("reportResult" + "reviewResult")
  → 拼接: 报告内容 + "---\n\n## 审核意见\n\n" + 审核内容
  → reportMapper.insert() → INSERT report表
  → analysisTaskMapper.updateById() → UPDATE task表

报告搜索:
  GET /api/reports/search?keyword=华为
  → wrapper.eq(userId).and(w -> w.like(companyName).or().like(content))
  → SQL: WHERE user_id=? AND (company_name LIKE ? OR content LIKE ?)
  ↑ and()包裹or()防止OR破坏user_id隔离

成本控制:
  创建前: checkDailyLimit() → callCount >= 100? 拦截
  完成后: addUsage() → INSERT or UPDATE ai_usage_daily
```

### 2.5 前端闭环链路

```
4页SPA: loginPage / mainPage / detailPage / searchPage + userPage
页面切换: display:none/block

核心闭环:
  输入公司名 → createTask() → POST /api/tasks
  → 自动跳转详情页 → openTask(id, name, "RUNNING")
  → connectSse(taskId) → EventSource(url?token=xxx)
  → 监听"agent"事件 → 更新4步进度条
  → 监听"result"事件 → loadReport()
  → onerror兜底 → setTimeout轮询GET /api/tasks/{id}

报告渲染:
  cleanReportContent() → 移除JSON代码块 + 格式化裸露JSON
  → formatJsonToHtml() → 评分用score-grid, 问题用issue-card
  → marked.parse() → Markdown转HTML → CSS样式渲染
```

---

## 三、重要概念的人话解释

| 术语 | 人话 |
|------|------|
| ReAct模式 | AI先"想"再"做"再"看结果"，循环往复直到信息足够。不是一次性输出，而是推理-行动-观察的螺旋上升 |
| Pipeline编排 | 像流水线一样，4个Agent依次干活，前一个的产出是后一个的原料。可配重试次数和是否可跳过 |
| 降级策略 | review Agent失败了不影响整体，报告照样出，只是标注"未经审核"。核心流程不能降级，锦上添花的可以 |
| SSE | 服务器单向推消息给浏览器，比WebSocket简单。前端用EventSource接收，后端用SseEmitter发送 |
| AgentContext.outputs | Agent间的"共享白板"，ConcurrentHashMap，每个Agent把自己的结果写上去，下一个Agent从上面读 |
| @Async | Spring的异步注解，Pipeline在子线程跑，不阻塞HTTP请求。前端创建任务后秒回，后台慢慢分析 |
| BCrypt | 密码哈希算法，自带随机盐。同一个密码每次哈希结果不同，但验证都能通过。防彩虹表+防暴力破解 |
| @JsonIgnore | Jackson序列化时忽略这个字段。API返回用户对象时password字段自动消失，防泄露 |
| @PostConstruct | Bean初始化后自动执行的方法。用来做启动时检查，比如JWT密钥强度不够就报警 |
| wrapper.and()包裹or() | MyBatis-Plus的坑：直接or()会破坏前面的AND条件。用and(w->w.like().or().like())确保OR在AND内部 |

---

## 四、精读中发现的代码细节

### 4.1 JwtInterceptor的双重取token策略

```java
// 先从Header取
String authHeader = request.getHeader("Authorization");
if (authHeader != null && authHeader.startsWith("Bearer ")) {
    token = authHeader.substring(7);
}
// Header没有，从query参数取
if (token == null) {
    token = request.getParameter("token");
}
```

**为什么**：普通请求用Header，SSE用query参数（EventSource不支持自定义Header）。一个拦截器兼容两种场景。

### 4.2 PipelineConfig的Listener匿名类

```java
pipeline.listener(new PipelineListener() {
    @Override
    public void onAgentStart(Long taskId, String agentName) {
        // UPDATE current_agent + SSE推送
    }
    // ...
});
```

**为什么用匿名类而不是Lambda**：PipelineListener有3个方法，Java的函数式接口只能1个方法。匿名类可以全部实现。

### 4.3 ReActExecutor的未知Action降级

```java
private String executeAction(String action, String actionInput) {
    if ("search_web".equalsIgnoreCase(action)) {
        return tavilyService.search(actionInput);
    }
    log.warn("[ReAct未知Action] action={}，降级为搜索", action);
    return tavilyService.search(actionInput);  // 未知action也当搜索处理
}
```

**为什么**：AI可能输出不在预定义列表里的action，降级为搜索比直接报错更安全。

### 4.4 AnalyzeAgent的getCollectResult防御性取值

```java
private String getCollectResult(AgentContext context) {
    Object collectOutput = context.getOutputs().get("collect");
    if (collectOutput instanceof AgentResult agentResult) {
        Object collectResult = agentResult.getData().get("collectResult");
        if (collectResult instanceof String s) {
            return s;
        }
    }
    return "（未找到采集结果，请基于公司名称自行搜索分析）";
}
```

**为什么**：如果collect被跳过或失败，outputs里可能没有数据。给个兜底提示，让AI自己搜索。

### 4.5 前端cleanReportContent的5步清洗

```
1. 移除 ```json ... ``` 代码块（AI有时把JSON包在代码块里）
2. 检测裸露JSON → JSON.parse() → formatJsonToHtml()
3. 移除残留的JSON代码块
4. 移除降级标记（⚠️ / Review Agent...）
5. 清理多余空行
```

**为什么**：AI生成的报告格式不可控，可能混入原始JSON。不清洗的话marked会原样渲染，用户看到一堆大括号。

---

## 五、值得记住的经验

### 5.1 代码精读的正确姿势

不是"看一遍代码"，而是**画出完整链路**：
1. 前端请求打到哪里（URL → Controller方法）
2. Controller做了什么（取参数、调Service）
3. Service做了什么（业务逻辑、调Mapper）
4. Mapper执行了什么SQL（SELECT/INSERT/UPDATE哪张表）
5. 数据库哪张表发生了变化
6. 最终为什么页面会更新（SSE推送/前端轮询/返回值渲染）

**如果一条链路讲不出来，就还没真正理解。**

### 5.2 面试讲项目的结构

```
1. 一句话介绍项目（10秒）
2. 三个技术亮点（每个1分钟）
   - 亮点是什么
   - 为什么这么做
   - 遇到什么问题、怎么解决
3. 主动抛一个"如果重新设计"的话题（展示思考深度）
```

### 5.3 每个亮点都要准备追问

| 亮点 | 必问追问 |
|------|---------|
| 自研ReAct | 为什么不直接让AI一次性输出？正则解析失败怎么办？和LangChain比优劣？ |
| Pipeline+降级 | 为什么串行不是并行？降级怎么实现的？SSE和WebSocket怎么选？ |
| 成本控制+安全 | 有并发问题吗？wrapper.and()包裹or()是什么？BCrypt比MD5好在哪？ |

### 5.4 精读发现的可优化点

| 优化点 | 当前 | 改进 |
|-------|------|------|
| @Async → 消息队列 | 内存线程池，重启丢任务 | RabbitMQ/Kafka持久化 |
| LIKE搜索 → 全文检索 | 大数据量LIKE性能差 | MySQL全文索引或ES |
| 前端无XSS防护 | marked直接渲染HTML | 加DOMPurify消毒 |
| 成本控制非原子 | 检查和记账有超限窗口 | 分布式锁或Redis原子操作 |
| 无refreshToken | JWT过期只能重新登录 | 双token机制 |

---

## 六、还不熟、下次还要追问的问题

1. **Spring AI ChatClient的内部机制**：ChatClient.prompt().messages().call() 底层怎么发HTTP请求？连接池怎么配？超时怎么设？

2. **SseEmitter的线程模型**：SseEmitter创建在HTTP线程，sendEvent在@Async线程调用，ConcurrentHashMap保证线程安全，但SseEmitter本身的send方法是线程安全的吗？

3. **ReAct vs Function Calling**：OpenAI的Function Calling是工具调用的标准方案，和自研ReAct正则解析比，各有什么优劣？什么时候该用Function Calling？

4. **Agent的Memory管理**：当前ReActExecutor用ArrayList存对话历史，多轮对话会越来越长。怎么截断？Token超限怎么办？

5. **Pipeline的并行化可能**：如果collect可以拆成多个子任务（公司简介/竞品/动态分别采集），怎么并行执行后再合并？CompletableFuture？ForkJoin？

6. **生产环境的JWT方案**：当前单机JWT，如果部署多实例怎么办？Redis存token？JWT无状态的优势就没了？

7. **AI输出的确定性**：ReAct循环中AI可能不按格式输出（不写Thought直接写Action），怎么提高格式遵从率？更严格的Prompt？Few-shot示例？

---

## 七、Day 15 总结

Day 15 的核心不是写代码，而是**把15天积累的代码变成能讲出来的知识**。

精读5条链路后发现：
- 代码能跑 ≠ 能讲清链路
- 很多设计决策（降级、双引擎搜索、wrapper.and包裹or）写的时候是"踩坑后修的"，精读后才能讲出"为什么这么设计"
- 面试话术不是背答案，而是从代码中提炼出"问题→方案→权衡"的完整叙事

**项目15天，从0到完整系统，最终收获的不是代码本身，而是"怎么把一个想法变成可运行、可演示、可面试的项目"这套方法论。**
