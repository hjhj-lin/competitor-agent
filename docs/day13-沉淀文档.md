# Day 13 沉淀文档：前端聊天页面（从零到一）

---

## 一、今天做了哪些功能

从"只有后端 9 个 API"到"浏览器能完成完整闭环"，一个 `index.html` 搞定。

```
之前：用户只能用 PowerShell / Apifox 调接口，没有可视化界面
现在：浏览器打开 http://localhost:8080/index.html，登录→创建任务→SSE进度→报告→搜索→删除，一气呵成
```

| 功能             | 前端位置             | 调用的 API                          |
| ---------------- | -------------------- | ----------------------------------- |
| 登录             | 登录页               | `POST /api/auth/login`              |
| 注册             | 登录页切换"立即注册" | `POST /api/auth/register`           |
| 创建任务         | 首页搜索框           | `POST /api/tasks`                   |
| 任务列表（分页） | 首页历史分析区       | `GET /api/tasks`                    |
| SSE 实时进度     | 创建后自动跳到详情页 | `GET /api/tasks/{id}/stream?token=` |
| 查看报告         | 详情页自动展示       | `GET /api/tasks/{id}`               |
| 删除任务         | 任务卡片删除按钮     | `DELETE /api/tasks/{id}`            |
| 搜索报告         | 搜索报告页           | `GET /api/reports/search`           |
| 报告详情         | 搜索页点击结果       | `GET /api/reports/{id}`             |
| 用户列表         | 用户管理页           | `GET /api/users`                    |
| 用户详情         | 点击用户弹窗         | `GET /api/users/{id}`               |

**11 个 API，全部接上前端。**

---

## 二、关键代码逻辑链路

### 2.1 页面结构

```html
<!-- 四个页面，用 display:none 切换 -->
<div id="loginPage">
  <!-- 登录/注册表单 -->
  <div id="mainPage">
    <!-- 首页：创建+列表+分页 -->
    <div id="detailPage">
      <!-- 分析详情：四步进度+报告 -->
      <div id="searchPage">
        <!-- 报告搜索页 -->
        <div id="userPage">
          <!-- 用户管理页 -->

          <!-- 切换函数 -->
          function showPage(pageId) { ['loginPage', 'mainPage', 'detailPage',
          'searchPage', 'userPage'] .forEach(id =>
          document.getElementById(id).style.display = 'none');
          document.getElementById(pageId).style.display = 'block'; }
        </div>
      </div>
    </div>
  </div>
</div>
```

### 2.2 登录链路

```
用户输入 admin/admin123 → 点登录
  → fetch POST /api/auth/login {username, password}
  → AuthController.login() → SysUserService.login() → BCrypt校验 → 生成JWT
  → 返回 {code:200, data:{token:"eyJ..."}}
  → JS: token = res.data.token
  → JS: localStorage.setItem('token', token)
  → JS: showPage('mainPage')  // 切换到首页
  → JS: loadTasks()  // 加载任务列表
```

### 2.3 创建任务 + SSE 实时进度链路

```
输入"特斯拉" → 点"开始分析"
  → fetch POST /api/tasks {companyName:"特斯拉"} + Bearer token
  → AnalysisTaskService.createTask() → checkDailyLimit → INSERT task → @Async execute
  → 返回 {code:200, data:{id:20, status:"RUNNING"}}
  → JS: showPage('detailPage')  ← 关键！跳到详情页，不是留在首页
  → JS: 重置四个 Agent 图标为"等待中"
  → JS: connectSse(20)

Pipeline 异步线程：
  → onAgentStart("collect")
    → UPDATE analysis_task SET current_agent='collect'
    → SSE send: event:agent, data:{"agent":"collect","status":"RUNNING"}
  → 浏览器: currentSse.addEventListener('agent', function(e) {
        markAgentRunning(data.agent)  // 图标变黄色+旋转动画
    })
  → onAgentComplete("collect")
    → SSE send: event:agent, data:{"agent":"collect","status":"COMPLETED"}
  → 浏览器: markAgentDone('collect')  // 图标变绿色+✓
  → ... analyze → report → review 同样流程 ...
  → onPipelineComplete → SSE send: event:result, data:{"status":"COMPLETED","aiCallCount":16}
  → 浏览器: currentSse.close()
  → 浏览器: fetch GET /api/tasks/20 → marked.parse(result) → 渲染报告
```

### 2.4 审核意见 JSON 格式化链路

````
报告内容 → cleanReportContent(raw)
  → 1. 移除 ```json ... ``` 代码块
  → 2. 正则匹配裸露的 JSON 对象
    → JSON.parse() 成功后调用 formatJsonToHtml(obj)
      → 如果是评分对象（值含数字+key含"分"）→ review-score-grid 网格卡片
      → 如果是问题对象（含"问题编号/问题描述/优先级"）→ issue-card 卡片
      → 其他键值对 → <strong>key：</strong> value
  → 3. 清理降级标记和多余空行
  → marked.parse() 渲染 Markdown → 显示 HTML
````

### 2.5 EventSource 鉴权

浏览器 `EventSource` API 不支持自定义 Header，走了 query 参数鉴权：

```javascript
// 前端
const url = '/api/tasks/' + taskId + '/stream?token=' + token;  // ← token 放 URL
new EventSource(url);

// 后端 JwtInterceptor
String token = request.getHeader("Authorization");  // 先取 Header
if (token == null) {
    token = request.getParameter("token");  // Header没有就取 query 参数
}
```

---

## 三、重要概念的人话解释

| 术语                           | 人话                                                                            |
| ------------------------------ | ------------------------------------------------------------------------------- |
| SPA（Single Page Application） | 一个 HTML 文件包含所有功能，通过 display:none 切换"页面"，不用跳转              |
| EventSource                    | 浏览器原生 SSE API，只能发 GET 请求，不支持自定义 Header                        |
| SSE event type                 | `event:agent\ndata:{...}\n\n` 格式，前端用 `addEventListener('agent', fn)` 监听 |
| marked.js                      | 把 Markdown 文本转 HTML 的 JS 库，CDN 引入零依赖                                |
| cleanReportContent             | 在 marked.parse() 之前先清理原始 JSON，防止页面显示乱码                         |
| formatJsonToHtml               | 识别 JSON 结构，把评分、问题列表等渲染为带样式的卡片                            |
| display:none 切换              | 伪多页面方案，所有"页面"都是 div，切换就是隐藏A显示B                            |
| localStorage                   | 浏览器本地存储，token 存这里，刷新页面不用重新登录                              |
| 毛玻璃登录页                   | `backdrop-filter: blur(20px)` + 半透明白色背景，现代设计手法                    |

---

## 四、联调中踩过的坑

### 坑1：EventSource 跨域限制

**现象**：前端页跳转详情页后，`new EventSource()` 报跨域错误。

**原因**：切到 detailPage 后，浏览器认为在不同页面，跨域策略收紧。

**修复**：确认 URL 是同域（都是 `http://localhost:8080`），EventSource 不走跨域检查。实际问题是 token 变量在函数间丢失。

### 坑2：SSE 断开后状态不一致

**现象**：`onerror` 回调触发时任务其实已完成，但前端还在显示"执行中"。

**修复**：`onerror` 里延迟 2 秒再查一次 `GET /api/tasks/{id}`，如果状态是 COMPLETED/FAILED 就更新 UI。

```javascript
currentSse.onerror = function () {
  currentSse.close();
  setTimeout(() => {
    fetch("/api/tasks/" + taskId).then((res) => {
      if (res.data.status === "COMPLETED") {
        setDetailStatus("COMPLETED");
        loadReport();
      }
    });
  }, 2000);
};
```

### 坑3：BCrypt 哈希中的 $ 符号被 PowerShell 转义

**现象**：`UPDATE sys_user SET password='$2a$10$...'` 执行后 password 变成空字符串。

**原因**：PowerShell 把 `$2a` 当作变量名解析了。

**修复**：SQL 写到文件，用 `Get-Content file.sql | mysql` 管道导入，避免命令行转义。

### 坑4：注册功能的密码校验

**现象**：前端注册输入 5 位密码，后端返回 400。

**原因**：`RegisterRequest` 有 `@Size(min=6)` 校验，前后端一致校验。

**教训**：前端加 `password.length < 6` 校验只是提升体验，后端校验才是安全底线。

---

## 五、值得记住的经验

### 5.1 一个 HTML 搞定前端（不一定要用 Vue/React）

对于 10 个 API 以下的小项目，纯 HTML + fetch + CSS 比引入框架省事：

- 不用 npm install / webpack / vite
- 不用组件拆分 / 状态管理
- 部署就是复制一个文件
- 代码量约 1500 行，可维护性 OK

### 5.2 display:none 切换比 SPA 路由简单

四个 `div` 放一个 HTML，`showPage(id)` 一行切换。比 Vue Router / React Router 省 90% 的复杂度。

### 5.3 JSON 格式化渲染是 UX 的核心

报告里混着 Agent 输出的 JSON 是致命伤。`cleanReportContent` + `formatJsonToHtml` 两个函数解决：

- 正则匹配裸露 JSON → JSON.parse() → 根据结构选择渲染模板
- 评分对象 → 网格卡片
- 问题对象 → issue-card
- 其他 → 普通键值对

### 5.4 搜索条件用 `and(w -> w.like().or().like())` 防 OR 泄漏

```java
wrapper.and(w -> w.like(Report::getCompanyName, keyword)
                    .or()
                    .like(Report::getContent, keyword));
```

生成 SQL: `WHERE user_id=? AND (company_name LIKE ? OR content LIKE ?)`

如果写成 `wrapper.like().or().like()`，OR 会破坏 user_id 隔离条件。

### 5.5 注册功能复用登录页

不用新建注册页面，同一个表单切换模式：`isRegisterMode = !isRegisterMode` 切换：

- 按钮文本："登录" ↔ "注册"
- 邮箱字段：隐藏 ↔ 显示
- 调用的 API：`/api/auth/login` ↔ `/api/auth/register`

---

## 六、还不熟、下次还要追问的问题

1. **marked.js 的 XSS 风险**：直接渲染任意 Markdown 到 innerHTML 有被注入风险。AI 生成的报告理论上安全，但如果报告内容被篡改可能有问题。需要 DOMPurify 做二次过滤。

2. **前端大文件拆分**：当前单个 index.html 约 1500 行，功能再增就会失控。什么量级应该拆分？CSS→JS→HTML 各一个文件？

3. **CDN 依赖的风险**：marked.js 从 CDN 加载，如果 CDN 挂了页面渲染就废了。应该打包到 static 目录还是继续用 CDN？

4. **移动端适配**：当前页面没有做响应式，手机上打开体验很差。做不做？如果做，是用媒体查询还是单独建一个 mobile 页？
