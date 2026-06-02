# Day6 沉淀文档 — Spring AI 集成 DeepSeek

## 一、今天做了哪些功能

| 步骤 | 内容 | 验证结果 |
|------|------|----------|
| 6-1 | Spring Boot 升级 3.4.5 + Spring AI 1.1.3 + DeepSeek 配置 | 项目能启动 |
| 6-2 | 最简 AI 接口 GET /api/ai/chat | DeepSeek 返回文字回复 |
| 6-3 | 创建任务后自动调 AI 分析 | 任务 status=COMPLETED, result 有内容 |
| 6-4 | AI 失败处理 + Spring AI 重试 + maxTokens 限制 | try-catch 兜底，重试3次 |

## 二、关键代码逻辑链路

### 5 个核心文件的关系

```
application-dev.yml          ← 配置中心：AI 参数、重试、模型
    ↓ 自动配置
SpringAiConfig.java          ← Bean 工厂：创建 ChatClient
    ↓ 注入
AiService.java               ← AI 能力层：封装 chatClient 调用 + 提示词
    ↓ 注入                    ↓ 注入
AnalysisTaskService.java     ← 业务编排层：任务 CRUD + AI 调用 + 状态机
    ↑ 注入
AiController.java            ← 调试入口：直接暴露 AI 能力（开发用）
```

### 创建任务 + AI 分析的完整链路

```
前端 POST /api/tasks + {"companyName":"Tesla"}
    ↓
JwtInterceptor → 解析 token → currentUserId=4
    ↓
TaskController.createTask(userId, request)
    ↓
AnalysisTaskService.createTask()
    → 1. 组装 task，status=RUNNING
    → 2. insert(task)  ← 先入库，告诉数据库"任务在执行中"
    → 3. try {
           aiService.analyzeCompany("Tesla")  ← 调 AI
           status = COMPLETED, result = AI回复, aiCallCount = 1
         } catch {
           status = FAILED  ← 失败也不崩，状态标记
         }
    → 4. updateById(task)  ← 更新最终状态
    ↓
AiService.analyzeCompany()
    → 拼提示词："请对「Tesla」进行简要竞品分析..."
    → chat(prompt)
        → chatClient.prompt().user(message).call().content()
        ↓
        Spring AI 内部：
        → HTTP POST https://api.deepseek.com/v1/chat/completions
        → 请求体: {model:"deepseek-chat", messages:[{role:"user",content:"..."}], max_tokens:500, temperature:0.7}
        → 等待响应（3-10秒）
        → 解析响应 JSON：choices[0].message.content
    ↓
返回 Result.success(AnalysisTaskVO)
```

### AI 配置的自动配置链

Spring AI 的自动配置做了这些事（不需要我们写代码）：

```
application-dev.yml 读取配置
    ↓
spring.ai.openai.api-key → 创建 OpenAiApi（HTTP 客户端，负责发请求）
    ↓
OpenAiApi → 创建 OpenAiChatModel（聊天模型，负责拼请求体、解析响应）
    ↓
OpenAiChatModel → 创建 ChatClient.Builder（Builder 模式，允许定制）
    ↓
SpringAiConfig → ChatClient.Builder.build() → ChatClient（最终使用的客户端）
```

我们只需要写 SpringAiConfig 里那 3 行代码，因为 Spring AI 1.0.0+ 不再自动创建 ChatClient Bean，只自动创建 Builder。强制你显式 `.build()`，让你有机会在 build 之前设置默认 Advisor（比如记忆、日志）。

### createTask 的状态机设计

```
Day5 的设计：创建任务 → status=PENDING（待执行）
Day6 的设计：创建任务 → status=RUNNING（执行中）→ COMPLETED 或 FAILED
```

为什么改了？Day5 没有 AI 调用，创建就是创建，状态当然是 PENDING。Day6 创建后立刻调 AI，直接进入 RUNNING。PENDING 留给将来"异步执行"——用户创建任务后排队等待，状态 PENDING；轮到执行时变 RUNNING。

### "先入库再执行"模式

```java
task.setStatus(RUNNING);
analysisTaskMapper.insert(task);       // ← 先入库
try {
    String result = aiService.analyzeCompany(...);
    task.setStatus(COMPLETED);
} catch (Exception e) {
    task.setStatus(FAILED);
}
analysisTaskMapper.updateById(task);   // ← 更新最终状态
```

为什么先 insert 再 updateById？如果 AI 调用中途服务崩溃，数据库里至少有一条 status=RUNNING 的记录，说明"这个任务曾经启动过但没完成"。如果先调 AI 再 insert，崩溃时数据库什么都没有，用户不知道发生了什么。

## 三、重要概念的人话解释

### 1. DeepSeek 兼容 OpenAI 格式
- 人话：DeepSeek 的 API 接口跟 OpenAI 长得一模一样，只是地址不同
- 所以用 spring-ai-starter-model-openai，把 base-url 改成 DeepSeek 的就行
- 这叫"OpenAI 兼容模式"，很多国产大模型都支持（通义、智谱、百川等）
- 将来换模型，只改 base-url + model 名，代码不用动

### 2. ChatClient 的四步调用
```java
chatClient.prompt()    // 1. 创建一个 Prompt 请求
    .user(message)     // 2. 设置用户消息（角色=user）
    .call()            // 3. 同步调用，发 HTTP 请求，等 AI 回复完再继续
    .content();        // 4. 从响应 JSON 里取出 choices[0].message.content
```
对应的还有 `.stream()` = 流式调用，AI 边生成边返回（SSE），Day8+ 会用到。

### 3. max-tokens — 限制 AI 回复长度
- 人话：告诉 AI "你最多说 500 个 token"，防止 AI 啰嗦，也省钱
- 1 token ≈ 0.75 英文单词，中文 1 字 ≈ 1-2 token
- 500 token ≈ 200-300 中文字，够用又不浪费

### 4. temperature — AI 的创造性
- 0 = 最确定（每次回答一样），适合代码生成、数据提取
- 1 = 最随机（每次回答不同），适合创意写作
- 0.7 = 平衡点，竞品分析需要一定创造性

### 5. 指数退避重试
```yaml
retry:
  max-attempts: 3              # 最多 3 次（含首次）
  backoff:
    initial-interval: 2s       # 第 1 次重试等 2 秒
    multiplier: 2              # 每次翻倍：2s → 4s → 8s
    max-interval: 30s          # 最多等 30 秒
```
为什么间隔要翻倍？如果 DeepSeek 服务器正在过载，1 秒后重试只会加重负担。2s→4s→8s，给服务器恢复的时间。这叫"指数退避"，是分布式系统的通用策略。

### 6. AiService 的两层方法设计
- `chat(message)` — 通用聊天，直接把 message 发给 AI
- `analyzeCompany(companyName)` — 业务方法，先拼提示词，再调 chat

为什么分两层？因为提示词是业务逻辑，不应该暴露给 Controller。Controller 只需要调 `analyzeCompany("Tesla")`，不需要知道提示词是"请对「Tesla」进行简要竞品分析..."。将来改提示词，只改 AiService，Controller 不用动。

## 四、联调中踩过的坑

### 坑1：Spring AI 版本跟 Spring Boot 版本强绑定
- 现象：Spring AI 1.0.0 + Spring Boot 3.2.5，Bean 创建失败
- 原因：Spring AI 1.0.0 依赖 Spring Framework 6.2，而 Boot 3.2 用的是 6.1
- 解决：升级 Spring Boot 到 3.4.5
- 教训：引入新框架前，先查它的 Spring Boot 兼容版本

### 坑2：ChatClient 不会自动创建 Bean
- 现象：启动报错 "No qualifying bean of type 'ChatClient'"
- 原因：Spring AI 1.0.0+ 不再自动创建 ChatClient Bean，只创建 Builder
- 解决：手动写 `@Bean ChatClient chatClient(ChatClient.Builder builder) { return builder.build(); }`
- 教训：Spring AI 的自动配置不是什么都帮你做，关键 Bean 要自己创建

### 坑3：OpenAiApi.Builder 没有 readTimeout 方法
- 现象：编译报错 "The method readTimeout(int) is undefined"
- 原因：Spring AI 1.0.0 的 OpenAiApi.Builder API 跟文档不一致，文档是旧版本的
- 解决：不手动创建 OpenAiApi Bean，用 Spring Boot 自动配置
- 教训：新框架的 API 可能跟文档不同步，编译报错比运行时报错好

### 坑4：创建任务时 AI 调用是同步的，会阻塞
- 现象：POST /api/tasks 要等 AI 回复（3-10秒）才返回
- 当前状态：先用同步，后续改异步
- 影响：前端会感觉创建任务很慢

## 五、值得记住的经验

### 1. 先入库再调 AI，不要反过来
如果先调 AI 再入库，AI 调用中途服务崩溃，数据库里什么都没有，用户不知道发生了什么。先入库 status=RUNNING，至少有记录可追踪。

### 2. 环境变量管理敏感信息
- API Key 用 `${DEEPSEEK_API_KEY}` 从环境变量取，不硬编码
- 没有默认值——故意的，没配 Key 就启动失败，比运行时报错好
- .gitignore 排除 .env 文件

### 3. AI 调用必须记耗时
```java
long start = System.currentTimeMillis();
String response = chatClient.prompt().user(message).call().content();
long duration = System.currentTimeMillis() - start;
log.info("[AI响应] duration={}ms", duration);
```
AI 是网络请求，可能 1 秒也可能 30 秒。不记耗时，出问题时完全不知道是 AI 慢还是数据库慢。

### 4. 版本升级要小步验证
- 升级 Boot 3.2→3.4 后，先 compile 确认旧代码不报错
- 加 Spring AI 依赖后，先启动确认 Bean 能注入
- 写 AI 接口后，先测 /api/ai/chat 确认 DeepSeek 能调通
- 再关联任务，测完整链路

## 六、还不熟、下次还要追问的问题

1. 当前 AI 调用是同步的，创建任务要等 3-10 秒。怎么改成异步？
   - 提示：@Async + CompletableFuture，或者用 Spring AI 的 stream() 做 SSE

2. AI 调用失败后，任务状态变成 FAILED，但用户没有重试机制。怎么让用户重新触发分析？
   - 提示：加一个 POST /api/tasks/{id}/retry 接口

3. max-tokens=500 限制了 AI 回复长度，但竞品分析可能需要更详细的报告。怎么平衡？
   - 提示：可以按任务类型动态设置 maxTokens，简单分析用 500，深度报告用 2000

4. Spring AI 的 stream() 流式调用怎么做？前端怎么接收？
   - 提示：SSE（Server-Sent Events），Controller 返回 Flux<String>

5. 如果 DeepSeek 服务挂了，有没有降级方案？
   - 提示：可以配置多个模型供应商，DeepSeek 挂了自动切到备用

6. SpringAiConfig 只有一行 builder.build()，将来怎么扩展？
   - 提示：可以在 build 前加 .defaultAdvisors()（记忆、日志等），这是 Spring AI 1.1.3 的 Advisor 机制
