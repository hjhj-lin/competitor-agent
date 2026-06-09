# Day 23 沉淀文档 — 多模型支持

## 一、今天做了哪些功能

| 操作 | 文件 | 变化 |
|------|------|------|
| 改造 | `framework/BaseReActAgent.java` | 加getModelName() + .options() per-request模型覆盖 |
| 改造 | `framework/ReportAgent.java` | getModelName() → "deepseek-chat" |
| 改造 | `framework/ReviewAgent.java` | getModelName() → "deepseek-chat" |
| 验证 | TaskId=57（拼多多） | COMPLETED，4个Agent全部成功 |

**一句话总结**：通过 `OpenAiChatOptions.builder().model(name)` per-request 覆盖模型，report/review用deepseek-chat深度推理，collect/analyze用默认flash快速便宜。

---

## 二、关键代码逻辑链路

### 多模型选择链路

```
Agent.execute()
  → getModelName()
    → null → 用默认模型 (deepseek-v4-flash)
    → "deepseek-chat" → .options(OpenAiChatOptions.builder().model("deepseek-chat").build())
  → chatClient.prompt().system().user().tools().options(chatOptions).stream()
  → DeepSeek API 收到 model 参数，用指定模型生成
```

### 关键代码：per-request模型覆盖

```java
var promptSpec = chatClient.prompt()
    .system(getSystemPrompt())
    .user(question)
    .tools(searchTools, readReportTool);

String modelName = getModelName();
if (modelName != null && !modelName.isBlank()) {
    promptSpec = promptSpec.options(OpenAiChatOptions.builder()
            .model(modelName)
            .build());
}

promptSpec.stream().content()...
```

---

## 三、重要概念的人话解释

### per-request 模型覆盖

通俗说：同一个ChatClient，每次请求可以指定不同模型。就像同一个电话号码，可以打给不同的人。

**vs 多ChatClient Bean**：多Bean方案需要创建多个ChatClient，用@Qualifier注入。per-request方案只需一个ChatClient，更简洁。

### 模型分配策略

| Agent | 模型 | 原因 |
|-------|------|------|
| collect | deepseek-v4-flash | 采集只需搜索+整理，快速便宜 |
| analyze | deepseek-v4-flash | 分析提炼较简单 |
| report | deepseek-chat | 报告需要深度推理，质量优先 |
| review | deepseek-chat | 审核需要逻辑判断，质量优先 |

---

## 四、值得记住的经验

### Spring AI 模型切换两种方式

| 方式 | 适用场景 | 复杂度 |
|------|---------|--------|
| `.options(ChatOptions)` | 同一提供商不同模型 | 低 |
| 多ChatClient Bean + @Qualifier | 不同提供商（DeepSeek/Kimi） | 中 |

### OpenAiChatOptions 是通用选项

虽然叫"OpenAi"ChatOptions，但所有OpenAI兼容API（DeepSeek/Kimi/Ollama）都用它。因为Spring AI用OpenAI协议作为标准接口。

---

## 五、还不熟、下次还要追问的问题

1. **跨提供商模型切换？** — DeepSeek和Kimi是不同base-url，per-request只能切模型不能切base-url
2. **模型降级？** — chat模型不可用时自动降级到flash？
3. **模型成本监控？** — 不同模型价格不同，如何统计每个Agent的token成本？
4. **动态模型配置？** — 模型名从数据库读取，而非硬编码？
