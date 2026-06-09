# Day 19 沉淀文档 — 流式输出前端优化（Markdown实时渲染）

## 一、今天做了哪些功能

| 操作 | 文件 | 变化 |
|------|------|------|
| 改造 | `index.html` `appendStreamingToken()` | `textContent` → `marked.parse()`，token累积后实时渲染Markdown |
| 改造 | `index.html` `.streaming-content` CSS | 去掉 `white-space: pre-wrap`（HTML渲染不需保留空白） |
| 改造 | `index.html` `streamingContent` div | 加 `report-content` 类，复用标题/表格/列表等Markdown样式 |
| 验证 | TaskId=44（腾讯） | COMPLETED，Result含完整Markdown表格/标题/列表 |
| 验证 | TaskId=45（美团） | COMPLETED，Result=10633字节 |

**一句话总结**：让前端流式区从"纯文本逐字显示"升级为"Markdown实时渲染"，用户体验从"看打字"升级为"看排版好的文档"。

---

## 二、关键代码逻辑链路

### 改动1：appendStreamingToken 改用 marked.parse()

```javascript
// 改动前：纯文本逐字追加
function appendStreamingToken(agent, token) {
  streamingText += token;
  const content = document.getElementById("streamingContent");
  content.textContent = streamingText;
  content.innerHTML += '<span class="streaming-cursor"></span>';
  content.scrollTop = content.scrollHeight;
}

// 改动后：Markdown实时渲染
function appendStreamingToken(agent, token) {
  streamingText += token;
  const content = document.getElementById("streamingContent");
  content.innerHTML = marked.parse(streamingText) + '<span class="streaming-cursor"></span>';
  content.scrollTop = content.scrollHeight;
}
```

**关键差异**：
- `textContent` → `innerHTML`：让 marked 生成的 HTML 标签生效
- `marked.parse(streamingText)`：每收一个 token 都把累积的完整 Markdown 重新解析一次
- 光标 `streaming-cursor` 拼接在 HTML 末尾，闪烁动画保持

### 改动2：streamingContent div 加双类名

```html
<!-- 改动前 -->
<div class="streaming-content" id="streamingContent"></div>

<!-- 改动后 -->
<div class="streaming-content report-content" id="streamingContent"></div>
```

**双类名职责分工**：
- `.streaming-content`：布局（max-height: 400px、overflow-y: auto、padding、background、border）
- `.report-content`：Markdown元素样式（h1/h2/h3、table、ul/ol、blockquote、code、pre等）

### 改动3：去掉 white-space: pre-wrap

```css
/* 改动前 */
.streaming-content {
  white-space: pre-wrap;  /* 保留所有空白符 */
  word-break: break-word;
}

/* 改动后 */
.streaming-content {
  word-break: break-word;  /* 移除 pre-wrap */
}
```

**为什么去掉**：纯文本模式需要 `pre-wrap` 保留换行和空格；HTML渲染模式浏览器会根据标签自动处理空白符，加 `pre-wrap` 反而会出现多余空行。

### 完整链路：后端推token → 前端渲染

```
[后端] BaseReActAgent.execute()
   ↓ chatClient.stream().content().doOnNext(token -> ...)
[SSE] sseEmitterService.sendEvent(taskId, "content", {agent, token})
   ↓ EventSource.addEventListener("content")
[前端] appendStreamingToken(agent, token)
   ↓ streamingText += token
[marked] marked.parse(streamingText) → HTML字符串
   ↓ content.innerHTML = HTML + '<span class="streaming-cursor">'
[DOM] .streaming-content.report-content 应用双类名样式
   ↓ content.scrollTop = content.scrollHeight
[用户] 看到实时渲染的Markdown报告 + 闪烁光标
```

---

## 三、重要概念的人话解释

### marked.js

通俗说：把 Markdown 文本（`# 标题`、`**加粗**`、`| 表格 |`）翻译成 HTML 字符串的工具。

本项目在 `<head>` 里通过 CDN 引入：
```html
<script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
```

**注意**：marked 默认是同步的，每次调用都重新解析整个文本。对于流式输出，每收一个 token 就解析一次，开销可接受（报告长度一般几千到几万字符）。

### 双类名复用样式

CSS 允许多个类共享元素：
```html
<div class="A B"></div>
```

A 和 B 类的样式会叠加应用。如果有冲突（同一属性），按 CSS 优先级规则后定义的类生效。

本项目里：
- `.streaming-content` 在行532定义（布局）
- `.report-content` 在行515定义（内容）

两个类的属性不冲突（一个管容器，一个管子元素），所以可以安全叠加。

### 流式渲染 vs 增量渲染

**流式渲染（当前方案）**：每收一个 token 就把累积的整个文本重新解析一次。
- 优点：实现简单，每次都是完整HTML
- 缺点：token多了之后性能下降（每次都全量解析）

**增量渲染（未来优化方向）**：只解析新到的 token 片段，追加到已有HTML后面。
- 优点：性能好
- 缺点：Markdown是上下文相关的（如 `**` 配对），增量解析会破坏格式

**当前选择**：流式渲染。报告长度可控（< 50KB），性能不是瓶颈。

### innerHTML + marked 的 XSS 风险

`marked.parse()` 会把 `# 标题` 翻译成 `<h1>标题</h1>`，但也会把 `<script>` 翻译成 `<script>` 直接执行。

**本项目是否安全？**
- 后端 prompt 限制了 AI 输出格式
- AI 通常不会输出恶意 HTML
- 风险存在但很低，生产环境建议用 DOMPurify 做二次过滤

---

## 四、联调中踩过的坑

### 坑1：white-space: pre-wrap 残留导致空行过多

**现象**：去掉了 `white-space: pre-wrap` 之前，渲染出来的 Markdown 报告标题之间有大量空行

**原因**：`pre-wrap` 保留所有空白符，包括 Markdown 源里的换行；marked 解析后的 HTML 在 `pre-wrap` 下会保留这些换行

**解决**：去掉 `pre-wrap`，让浏览器按 HTML 标签语义渲染空白

### 坑2：双类名继承导致字体大小异常

**现象**：第一次加 `report-content` 类时，`.streaming-content` 定义的 `font-size: 14px` 和 `.report-content` 定义的 `font-size: 14.5px` 冲突

**原因**：CSS 后定义优先，但两个类都是 font-size，具体看谁在文件后面

**解决**：本项目 `.report-content` 在 `.streaming-content` 之前定义（行515 vs 行532），所以 14.5px 生效，符合预期（报告字体稍大更易读）

### 坑3：DeepSeek 余额不足反复出现

**现象**：测试 TaskId=45 时 Tavily 搜索 3 次都返回，但 AI 调用失败

**原因**：DeepSeek API 余额又用完了（Day 18 就遇到过）

**解决**：用已经准备好的备用方案——直接看 Report Agent 的日志输出确认 Markdown 格式正确

---

## 五、值得记住的经验

### 1. 流式 Markdown 渲染的最小改动模板

```javascript
// 模板：3行代码搞定
function appendStreamingToken(token) {
  streamingText += token;
  content.innerHTML = marked.parse(streamingText) + '<span class="cursor">';
  content.scrollTop = content.scrollHeight;
}
```

**关键**：
- `streamingText` 累积原始 Markdown 文本
- 每次都重新 `marked.parse()` 整个文本
- 光标作为可控 HTML 拼接在末尾

### 2. 双类名 = 布局复用 + 内容样式复用

| 场景 | 方案 |
|------|------|
| 两个区域显示同类内容（报告/流式） | 共享一个 "内容样式类" + 各自的 "布局类" |
| 多个变体共享基础样式 | 基础类 + 修饰类（Bootstrap思想） |
| 主题切换 | 主题类 + 内容类 |

### 3. CSS 属性冲突优先级

同一个元素多个类时：
1. 内联样式 > ID选择器 > 类选择器
2. 后定义的类 > 先定义的类
3. 具体性相同时，后定义胜出

**经验**：把"基础样式"放前面，"覆盖样式"放后面。

### 4. 流式输出的性能瓶颈

| 阶段 | 瓶颈 |
|------|------|
| 后端 | LLM API响应速度（无法优化） |
| SSE传输 | 网络带宽（一般够用） |
| 前端渲染 | marked.parse() 每次全量解析 |

**优化方向**（未来）：
- 防抖：50ms内多次token合并渲染一次
- 增量diff：只渲染变化的HTML片段
- Web Worker：把 marked.parse() 放到 Worker 线程

---

## 六、还不熟、下次还要追问的问题

1. **marked.js 安全配置？** — 是否需要配置 `sanitize: true` 或集成 DOMPurify？当前是否有 XSS 风险？
2. **marked.parse() 的性能？** — 每次都全量解析，报告5万字符时会不会卡顿？
3. **流式渲染的视觉一致性？** — token 边界正好切在 `**` 中间时会怎样？marked 能正确处理吗？
4. **代码高亮？** — 报告里如果有 `python` 代码块，要不要加 highlight.js？
5. **流式渲染时的滚动行为？** — 用户手动往上滚查看历史时，新token到达是否还应该自动滚到底？
6. **marked 的扩展支持？** — 是否支持表格、任务列表、删除线等 GFM 语法？
7. **可访问性（a11y）？** — 屏幕阅读器如何朗读实时更新的 Markdown 内容？
8. **复制粘贴体验？** — 用户想复制流式区内容时，复制到的是渲染后的HTML还是原始Markdown？

---

## 七、Day 20 候选方向

参考 `二次开发总体规划.md`，Day 19 已完成 6个优化模块中的"前端流式体验"。

**候选方向**：
- **熔断 + 限流**：Resilience4j，给 Tavily/DeepSeek 调用加保护
- **Prompt 外部化**：把硬编码的 system prompt 抽到 `application.yml`，方便调优
- **多模型切换**：支持 OpenAI/Anthropic/通义千问，配置化切换
- **报告导出**：PDF/Word 导出，iText 或 Apache POI
- **测试覆盖**：单元测试（Agent）+ 集成测试（Pipeline）
