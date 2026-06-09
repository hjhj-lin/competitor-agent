# Day 18 沉淀文档 — 流式Token输出

## 一、今天做了哪些功能

| 操作 | 文件 | 变化 |
|------|------|------|
| 改造 | `BaseReActAgent.java` | `.call()` → `.stream().content().doOnNext().blockLast()`，逐token推SSE |
| 改造 | 4个Agent | 构造函数加SseEmitterService参数 |
| 改造 | `index.html` | 新增streaming-section + content事件监听 + 闪烁光标CSS |
| 验证 | TaskId=43 | Status=COMPLETED，Result=11891字节 |

**一句话总结**：用Spring AI的`.stream()`替代`.call()`，逐token推SSE到前端，用户体验从"干等"变"实时看到AI打字"。

---

## 二、关键代码逻辑链路

### 后端：BaseReActAgent.execute()

```java
chatClient.prompt()
    .system(getSystemPrompt())
    .user(question)
    .tools(searchTools, readReportTool)
    .stream()                          // ① 改为流式模式
    .content()                         // ② 只取内容（不含元数据）
    .doOnNext(token -> {               // ③ 每收到一个token就回调
        resultBuilder.append(token);   // 收集完整结果
        sseEmitterService.sendEvent(taskId, "content",
            Map.of("agent", getName(), "token", token));  // 推SSE
    })
    .blockLast();                      // ④ 阻塞等待流结束
```

**4步链路**：
1. `.stream()` — 切换到Flux流式模式
2. `.content()` — 只取文本内容（不要Message对象）
3. `.doOnNext(token)` — 逐token回调（每个token是String片段）
4. `.blockLast()` — 阻塞等待流结束（返回null，但结果已收集）

### 前端：SSE处理

```
EventSource监听:
  event: "agent"  → markAgentRunning() + showStreamingContent()
  event: "content" → appendStreamingToken()  ← 新增
  event: "agent"  → markAgentDone()
  event: "result" → loadReport() + hideStreamingContent()
```

**appendStreamingToken**：
```javascript
function appendStreamingToken(agent, token) {
  streamingText += token;
  const content = document.getElementById("streamingContent");
  content.textContent = streamingText;  // textContent防XSS
  content.innerHTML += '<span class="streaming-cursor"></span>';  // 加光标
  content.scrollTop = content.scrollHeight;  // 自动滚动
}
```

### Agent间数据传递

```
CollectAgent → outputs["collect"] → AnalyzeAgent 读取 collectResult
AnalyzeAgent → outputs["analyze"] → ReportAgent 读取 analysisResult
ReportAgent  → outputs["report"]  → ReviewAgent 读取 reportResult

4个Agent依次流式输出，每个Agent完成后收集完整结果存入context.outputs
```

---

## 三、重要概念的人话解释

### .call() vs .stream()

通俗说：
- `.call()`：AI生成完一整段话后一次性返回，像写信
- `.stream()`：AI一边生成一边吐出来，像打字机

### Flux / Mono（Reactor响应式）

通俗说：
- `Mono<T>`：只会产生1个值的异步流（`.call()`返回Mono）
- `Flux<T>`：会产生N个值的异步流（`.stream()`返回Flux）
- `.doOnNext()`：每收到一个值就执行回调
- `.blockLast()`：阻塞等待流结束（从异步变同步）

### textContent vs innerHTML

通俗说：
- `textContent`：原样显示文本，HTML标签会被当作普通字符（安全）
- `innerHTML`：解析HTML标签，可能执行脚本（危险）

为什么用`textContent`赋值再加光标：
```javascript
content.textContent = streamingText;                    // 安全渲染文本
content.innerHTML += '<span class="streaming-cursor"></span>';  // 追加HTML光标
```

---

## 四、联调中踩过的坑

### 坑1：Edit工具超时导致import未写入

**现象**：ReviewAgent缺少SseEmitterService的import，编译报ClassNotFoundException

**原因**：Edit工具第一次执行超时，但实际已经写入了一部分；第二次Edit时文件处于中间状态

**解决**：用Read确认文件当前状态，重新Edit补上import

### 坑2：DeepSeek API 402余额不足

**现象**：API返回"Account balance is insufficient"

**原因**：API账户余额用完

**解决**：充值后验证，任务43成功完成，Result=11891字节

### 坑3：PowerShell不支持&&连接命令

**现象**：`git checkout new-main && git merge dev && git push` 报错

**解决**：PowerShell用`;`或分步执行

---

## 五、值得记住的经验

### 1. Spring AI流式输出3步模板

```java
chatClient.prompt()
    .system(prompt).user(question).tools(tools)
    .stream().content()
    .doOnNext(token -> {/*处理token*/})
    .blockLast();
```

这是所有Spring AI项目都可以复用的模板。

### 2. SSE事件类型设计

| 事件 | 用途 |
|------|------|
| `agent` | Agent级别进度（开始/完成） |
| `content` | 内容级别（逐token推送） |
| `result` | 最终结果（Pipeline完成） |

三层事件，粒度从粗到细。

### 3. 前端防XSS

`textContent`渲染文本 + `innerHTML`仅追加可控HTML（如光标） = 安全

### 4. 可优化的方向

- HttpClient复用（TavilyService每次new一个）
- 流式输出改为Markdown渲染（目前是纯文本）
- SSE改为流式JSON格式，支持thinking/normal分离

---

## 六、还不熟、下次还要追问的问题

1. **Spring AI最大流式超时时间是多少？** — 流式输出通常比普通调用慢，5分钟超时够吗？
2. **Flux背压机制？** — 如果前端消费慢，Flux会自动减速吗？
3. **.stream()支持重试吗？** — 流式中断了能重试吗？还是只能从头开始？
4. **SseEmitter内存泄漏？** — 前端断开连接后SseEmitter会被清理吗？
5. **多Agent流式输出冲突？** — 4个Agent依次流式输出，前端怎么知道当前是哪个Agent的内容？（当前方案：showStreamingContent()重置streamingText）
6. **流式输出和工具调用的关系？** — 工具调用期间有token推送吗？还是只在AI推理时推送？
