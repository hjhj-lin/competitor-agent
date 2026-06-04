# Day 14 沉淀文档：全链路联调 + 异常测试

---

## 一、今天做了哪些功能

没有新功能代码，专注**修 Bug + 验证稳定性**：

```
之前：admin 密码失效、3 张表被删除、没有建表脚本
现在：admin 正常登录、init.sql 一键建库、7 个异常场景全部验证通过
```

| Step | 做了什么                             | 结果                                              |
| ---- | ------------------------------------ | ------------------------------------------------- |
| 14-1 | 修 admin 密码 + 写 init.sql 建表脚本 | admin/admin123 正常登录，new-main 分支同步        |
| 14-2 | 全链路正常流程验证                   | 登录→创建→SSE→报告→搜索→删除→跨用户保护，全部通过 |
| 14-3 | 7 个异常场景测试                     | 401/404/400 全部返回正确错误码和提示              |
| 14-4 | 提交 GitHub                          | commit 885cbe5                                    |

---

## 二、关键代码逻辑链路

### 2.1 数据库初始化链路

```sql
-- init.sql 执行顺序
CREATE DATABASE competitor_agent;
-- → 建 5 张表: sys_user / analysis_task / agent_execution / report / ai_usage_daily
-- → INSERT INTO sys_user (admin, BCrypt哈希, admin@example.com, daily_task_limit=50)
-- 使用方法: mysql -uroot -p < docs/init.sql
```

**为什么要写 init.sql？**

- 之前反复遇到"表不存在"的 500 错误，因为数据库在 clone 仓库时不会自动建
- 有了这个脚本，新人拿到项目后三秒起步：`mysql < init.sql` → `mvn spring-boot:run` → 打开浏览器

### 2.2 admin 密码修复链路

```
问题：admin 密码哈希被错误更新 → 无法登录
方案：注册临时用户 → 借用其 BCrypt 哈希 → 更新 admin
步骤：
  1. POST /api/auth/register {username:"temppwd", password:"admin123"}
     → SysUserService.register() → BCryptPasswordEncoder.encode("admin123")
     → INSERT INTO sys_user (username='temppwd', password='$2a$10$U2F1Md...')

  2. SELECT password FROM sys_user WHERE username='temppwd'
     → 拿到哈希: $2a$10$U2F1MdSNdHiriKee8nXTBOpQFpTRRMWjwraexMZ5gszLeXEQTs9Xe

  3. UPDATE sys_user SET password='$2a$10$U2F1Md...' WHERE username='admin'

  4. DELETE FROM sys_user WHERE username='temppwd'  -- 清理临时用户
```

**BCrypt 哈希为什么不能手动编？**

- BCrypt 包含随机 salt，每次 `encode("admin123")` 的结果都不同
- 但同一个哈希验证时都能通过，因为 salt 嵌入在哈希字符串开头
- 格式：`$2a$10$<22位salt><31位hash>`，验证时从哈希里提取 salt 重新算

### 2.3 全链路验证链路（正常流程）

```
1. 登录: fetch /api/auth/login {admin, admin123}
   → AuthController.login() → SysUserService.login()
   → SELECT * FROM sys_user WHERE username='admin'
   → BCryptPasswordEncoder.matches("admin123", storedHash) → true
   → JwtUtil.generateToken(userId=6) → token

2. 创建任务: fetch /api/tasks {companyName:"特斯拉"} + Bearer token
   → JwtInterceptor.preHandle() → 解析token → currentUserId=6
   → TaskController.createTask() → AnalysisTaskService.createTask()
   → aiUsageService.checkDailyLimit(6)
     → SELECT * FROM ai_usage_daily WHERE user_id=6 AND usage_date=CURDATE()
     → 0条记录 → 未超额 → 通过
   → INSERT INTO analysis_task (user_id=6, company_name="特斯拉", status="RUNNING")
   → @Async pipelineExecutor → 四个Agent串行执行

3. SSE 进度: 浏览器 → EventSource(/api/tasks/20/stream?token=xxx)
   → SseEmitterService.createEmitter(20) → 放入 ConcurrentHashMap
   → Pipeline执行中 → listener.onAgentStart("collect")
     → UPDATE analysis_task SET current_agent='collect'
     → sseEmitterService.sendEvent(20, "agent", {agent:"collect",status:"RUNNING"})
   → 依次: collect→analyze→report→review→COMPLETED

4. 查看报告: SSE收到result事件 → fetch /api/tasks/20
   → SELECT * FROM analysis_task WHERE id=20 → result字段
   → JS: cleanReportContent() → marked.parse() → 渲染HTML

5. 搜索报告: fetch /api/reports/search?keyword=特斯拉
   → ReportController.searchReports()
   → ReportService.searchReports()
   → SELECT * FROM report WHERE user_id=6 AND company_name LIKE '%特斯拉%'
   → 返回 1 条

6. 删除任务: fetch DELETE /api/tasks/20
   → UPDATE analysis_task SET deleted=1 WHERE id=20 AND user_id=6
   → 任务列表不再显示（查询时 WHERE deleted=0）
```

### 2.4 异常场景验证链路

```
异常1: 不带 token → JwtInterceptor → token=null → 返回 401 "未登录，请先登录"
异常2: 错误 token → JwtInterceptor → JwtUtil.validate() 失败 → 返回 401 "token无效或已过期"
异常3: 搜索不存在 → ReportService → SELECT LIKE → 0条 → 返回 {total:0, records:[]}
异常4: 不存在ID → AnalysisTaskService.getTaskById() → selectById返回null → throw(404)
异常5: 重复用户名 → SysUserService.register() → selectByUsername → 已存在 → throw(400)
异常6: 密码太短 → @Size(min=6) 校验失败 → MethodArgumentNotValidException → 400
异常7: 超额拦截 → AiUsageService.checkDailyLimit() → call_count>=100 → throw(400)
```

---

## 三、重要概念的人话解释

| 术语                | 人话                                                                         |
| ------------------- | ---------------------------------------------------------------------------- |
| 全链路              | 从浏览器输入到数据库写入再回到浏览器更新 UI 的完整过程                       |
| 异常测试            | 故意做错误操作（不传token、传错误token、超额创建），验证系统返回正确的错误码 |
| BCrypt salt         | 每个密码哈希里都有随机盐，同一个密码每次生成的哈希不同，但都能验证通过       |
| init.sql            | 数据库初始化脚本，新环境第一件事就跑它，保证表结构和默认数据一致             |
| 软删除（deleted=1） | 不是真删数据，而是标记 deleted=1，查询时加 WHERE deleted=0 过滤              |
| 跨用户保护          | 用户A不能删用户B的任务，Service 层查 selectById 后对比 userId                |
| 防御性编程          | 不信任任何输入，token 校验、userId 隔离、参数校验，每一层都防御              |

---

## 四、联调中踩过的坑

### 坑1：数据库表在不同仓库之间丢失

**现象**：`git checkout main` 后 `report`、`ai_usage_daily` 表丢失，创建任务报 500。

**原因**：两个分支历史不相关（main 和 new-main 没有共同祖先），checkout 时触发了 git 内部操作，.git 目录被破坏，重新 clone 后之前建的几张表没了。

**修复**：

1. 用 `SHOW CREATE TABLE` 导出所有表结构
2. 写入 `docs/init.sql` 作为持久化的建表脚本
3. 把 init.sql 提交到 Git，以后任何时候都能一键重建

**教训**：数据库是"外挂"的，不在 Git 里。建表脚本必须存一份在代码仓库里。

### 坑2：BCrypt 哈希中 $ 符号被 PowerShell 转义

**现象**：`mysql -e "UPDATE SET password='$2a$10$...'"` 执行后 password 为空。

**原因**：PowerShell 把 `$2a` 当作变量名解析为 `null`，实际执行的 SQL 变成 `UPDATE SET password='$10...'`。

**修复**：SQL 写入文件，用 `Get-Content | mysql` 管道传递，绕开命令行转义。

**教训**：所有包含 `$` 符号的 SQL 都不应该直接在 PowerShell 命令行里执行。写入 `.sql` 文件再导入。

### 坑3：SSE EventSource 的 onerror 不是"错误"

**现象**：SSE 正常完成后也触发 `onerror`，导致 UI 显示异常。

**原因**：EventSource 规范规定，连接关闭（正常或异常）都触发 `onerror`。服务器端 `SseEmitter.complete()` 正常关闭连接，浏览器也触发 `onerror`。

**修复**：`onerror` 里不直接报错，而是延迟查询一次任务状态：

```javascript
currentSse.onerror = function () {
  currentSse.close();
  setTimeout(() => {
    fetch(url).then((res) => {
      if (res.data.status === "COMPLETED") {
        showReport();
      }
    });
  }, 2000);
};
```

**教训**：EventSource 的 `onerror` 不能当"出错回调"用，要结合任务状态判断。

### 坑4：Invoke-RestMethod 的 Content-Type 陷阱

**现象**：PowerShell 发 `{"companyName":"腾讯"}`，数据库存的是"??"。

**原因**：`Invoke-RestMethod -Body '...'` 默认用 ASCII 编码发送 JSON，中文在传输过程乱码。

**修复**：`-Body [System.Text.Encoding]::UTF8.GetBytes('...')` 强制 UTF-8 编码发送。

**教训**：PowerShell 测试中文输入时，始终用 `GetBytes` 确保编码正确。但前端浏览器不受影响（fetch 默认 UTF-8）。

---

## 五、值得记住的经验

### 5.1 init.sql 是项目的"基础设施"

之前三天建了多张表，每次 clone 后就丢一部分。有了 init.sql：

- 新人上手：`mysql < init.sql` → 启动 → 可用
- 生产部署：DBA 审核后执行
- 协作开发：同事不需要猜表结构

### 5.2 异常测试要覆盖每一层

| 层次   | 测试场景                       |
| ------ | ------------------------------ |
| 网络层 | 不带 token → 401               |
| 鉴权层 | 错误 token → 401               |
| 参数层 | 密码太短、用户名重复 → 400     |
| 业务层 | 超额拦截、不存在资源 → 400/404 |
| 数据层 | 搜索无结果 → 空列表不报错      |

### 5.3 软删除优于硬删除

`DELETE /api/tasks/20` 实际执行 `UPDATE analysis_task SET deleted=1`：

- 可恢复（改回来 deleted=0 就行）
- 列表查询自动过滤 `WHERE deleted=0`
- 用户看不到但数据留底

### 5.4 服务层必须做 userId 隔离

```java
// ReportService
Report report = reportMapper.selectById(reportId);
if (report == null || !report.getUserId().equals(userId)) {
    return null;  // 不是你的报告，就当不存在
}
```

绝不信任前端传的 userId，始终用 JWT 解析出的 currentUserId。

### 5.5 异常测试是"信任感"的来源

不是"功能写完了"就完事了。用户会做各种奇怪操作，系统必须优雅处理：

- 不带 token 访问 → 401 而不是 500 堆栈
- 搜不存在的公司 → 空列表而不是报错
- 超限额创建 → 友好提示而不是偷偷扣费

---

## 六、还不熟、下次还要追问的问题

1. **数据库版本管理**：init.sql 适合初始化，但已有生产数据的数据库怎么升级表结构？Flyway / Liquibase 怎么选？

2. **测试覆盖率**：当前只做了手动 API 测试。JUnit 单元测试和集成测试应该覆盖到哪一层？Service 层还是 Controller 层？

3. **日志规范**：当前用 `log.warn/log.info`，但日志级别没有统一标准。什么场景用 warn，什么场景用 error？需不需要把关键操作日志存到数据库？

4. **前端错误提示优化**：当前 API 返回 400 时前端用 `alert()` 弹窗，体验不好。应该用 toast 通知还是内联错误提示？

5. **环境配置分离**：当前 `application-dev.yml` 硬编码了数据库密码和 API key。生产环境怎么处理？环境变量？配置中心？
