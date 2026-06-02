# Day 12 沉淀文档：报告存储 + 搜索 + 成本控制

---

## 一、今天做了哪些功能

从"报告只存在 task 表的 result 字段"升级为"报告独立存储 + 搜索 + 用量控制"：

```
之前：报告内容塞在 analysis_task.result 里，列表接口返回全部字段（含大段报告文本）
现在：report 表独立存储，支持按关键词搜索
      列表接口返回 AnalysisTaskListVO（不含 result），详情才返回完整数据
      每日 AI 调用限额 100 次，超限拦截
```

五个 Step 小步推进：

| Step | 功能 | 验证方式 |
|------|------|----------|
| 12-1 | report 表 + Report 实体 + ReportMapper | 建表成功，编译通过 |
| 12-2 | Pipeline 完成后写 report 表 | 查数据库有记录 |
| 12-3 | 列表接口不返回 result | API 返回 AnalysisTaskListVO，无 result 字段 |
| 12-4 | 报告搜索接口 | GET /api/reports/search?keyword=阿里 返回匹配结果 |
| 12-5 | 每日 AI 调用限额 | 超限返回 400 "今日AI调用次数已达上限" |

---

## 二、关键代码逻辑链路

### 2.1 报告存储链路

```
Pipeline 执行完成
  → PipelineExecutionService.executePipeline()
    → pipelineResult.isSuccess()?
      → extractFromPipeline("reportResult") + extractFromPipeline("reviewResult")
      → 拼接最终内容
      → UPDATE analysis_task SET status=COMPLETED, result=..., ai_call_count=...
      → INSERT report (task_id, user_id, company_name, content)   ← Day 12 新增
      → aiUsageService.addUsage(userId, aiCallCount)              ← Day 12 新增
    → SseEmitter.complete()
```

关键：先 updateById(task) 再 addUsage。顺序不能反——如果先加用量再更新任务，更新失败时用量已经加了。

### 2.2 报告搜索链路

```
GET /api/reports/search?keyword=阿里
  → JwtInterceptor 解析 token → currentUserId
  → ReportController.searchReports(userId, keyword, pageNum, pageSize)
    → ReportService.searchReports()
      → LambdaQueryWrapper:
          WHERE user_id = ? AND (company_name LIKE '%阿里%' OR content LIKE '%阿里%')
          ORDER BY created_at DESC
      → reportMapper.selectPage() → MyBatis-Plus 自动拼分页 SQL
      → page.convert(this::toVO) → 只返回 ReportVO 字段
```

### 2.3 成本控制链路

```
创建任务时（同步）：
  POST /api/tasks
    → AnalysisTaskService.createTask()
      → aiUsageService.checkDailyLimit(userId)
        → SELECT ai_usage_daily WHERE user_id=? AND usage_date=CURDATE()
        → call_count >= 100? → 抛 BusinessException(400)

Pipeline 完成后（异步）：
  → PipelineExecutionService.executePipeline()
    → task.getAiCallCount() > 0?
      → aiUsageService.addUsage(userId, callCount)
        → 今日无记录 → INSERT
        → 今日有记录 → UPDATE call_count = call_count + ?
```

---

## 三、重要概念的人话解释

| 术语 | 人话 |
|------|------|
| 报告独立存储 | 以前报告塞在任务表的 result 字段里，查报告要查任务表；现在单独一张 report 表，搜索更灵活 |
| AnalysisTaskListVO | 列表用的"瘦"对象，只有 id/companyName/status 等轻量字段，不背 result 那个大包袱 |
| IPage | MyBatis-Plus 的分页容器，里面有 records(当前页数据) + total(总条数) + pages(总页数) |
| LambdaQueryWrapper.and(w -> w.like().or().like()) | 先用 and 包一层，里面用 or 连接。生成的 SQL 是 `WHERE A AND (B OR C)`，OR 不会破坏外层条件 |
| DAILY_LIMIT = 100 | 每人每天最多调 100 次 AI。DeepSeek API 按 token 计费，不加限制可能一天烧光预算 |
| "先放行后计费" | 创建任务时只检查"当前是否超限"，不预扣额度。如果限额剩 5 次但一次 Pipeline 要调 20 次，任务仍会创建成功 |
| page.convert(this::toVO) | MyBatis-Plus Page 对象的转换方法，遍历每条记录调用 toVO()，返回新的 IPage<VO> |

---

## 四、联调中踩过的坑

### 坑1：AnalysisTaskListVO import 缺失

**现象：** 编译报错找不到 `AnalysisTaskListVO` 类。

**原因：** 之前用 SearchReplace 添加 import 时，可能没成功写入。文件里用了 `AnalysisTaskListVO` 但没有 import 语句。

**修复：** 重新添加 `import com.competitor.agent.vo.AnalysisTaskListVO;`。

**教训：** SearchReplace 编辑后要编译验证，不能假设"改了就对了"。特别是 import 这种不影响逻辑但影响编译的行，容易遗漏。

### 坑2：MyBatis-Plus 的 OR 条件破坏用户隔离

**现象（预判）：** 如果搜索条件写成 `wrapper.like(companyName).or().like(content)`，生成的 SQL 是 `WHERE user_id = ? AND company_name LIKE ? OR content LIKE ?`，OR 会让 content 的匹配绕过 user_id 限制。

**修复：** 用 `wrapper.and(w -> w.like(companyName).or().like(content))`，生成 `WHERE user_id = ? AND (company_name LIKE ? OR content LIKE ?)`。

**教训：** MyBatis-Plus 条件构建器里，OR 的作用范围是"从上一个 AND 开始"。需要 OR 只作用于部分条件时，必须用 `and(Consumer)` 或 `or(Consumer)` 包一层 Lambda。

### 坑3：PowerShell 不支持 && 语法

**现象：** `cd dir && mvn compile` 报错"标记'&&'不是此版本中的有效语句分隔符"。

**原因：** PowerShell 5.x 不支持 `&&`，这是 bash/cmd 的语法。PowerShell 7+ 才支持。

**修复：** 用分号 `;` 代替：`cd dir; mvn compile`。

**教训：** Windows 环境下注意 PowerShell 版本差异。

---

## 五、值得记住的经验

### 5.1 报告和任务分表存储

任务（task）是"过程"，报告（report）是"结果"。分表的好处：
- 搜索报告不用 JOIN 任务表
- 列表接口不用背 result 大字段
- 未来报告可以独立管理（分享、导出、版本）
- 任务表可以只存摘要或状态

### 5.2 列表接口用瘦 VO，详情接口用胖 VO

`AnalysisTaskListVO`（列表）vs `AnalysisTaskVO`（详情）：
- 列表一次返回 10~50 条，每条带 result（几千字）就是几十 KB 浪费
- 详情只返回 1 条，带 result 没问题
- `page.convert(this::toVO)` 一行搞定转换

### 5.3 成本控制的"先放行后计费"模式

当前设计：创建任务时只检查"是否已超限"，不预扣额度。和运营商流量一个逻辑——你先用，用完我再统计。好处是简单，坏处是可能超额。如果要"先扣后用"，需要在创建任务时预估算 AI 调用次数并冻结额度，复杂度高很多。

### 5.4 LIKE 搜索够用但不高效

当前用 `company_name LIKE '%keyword%'` 做模糊搜索，数据量小时没问题。如果 report 表到万级以上，LIKE 全表扫描会慢。升级方案：
- MySQL FULLTEXT 索引 + `MATCH ... AGAINST`
- Elasticsearch 全文检索
- 但现阶段不要过度优化，YAGNI（You Aren't Gonna Need It）

---

## 六、还不熟、下次还要追问的问题

1. **并发累加用量问题：** 两个 Pipeline 同时完成，都读到 callCount=95，都加 10，最终变成 105 而不是 100。当前单用户串行场景不会触发，但多用户并发时需要用 `UPDATE SET call_count = call_count + ?` 原子更新。

2. **报告内容去重：** task 表的 result 和 report 表的 content 存了相同内容。如果报告很长，这是双倍存储。是否应该 task 表只存摘要，report 表存完整内容？

3. **搜索结果高亮：** 当前搜索只返回匹配的报告，没有标记关键词在哪个位置。前端展示时用户不知道匹配在哪里。需要加高亮或摘要片段（snippet）。

4. **限额配置化：** DAILY_LIMIT = 100 是硬编码。应该放到 application.yml 里，不同用户等级可以不同限额（免费用户 50 次，付费用户 500 次）。

5. **用量统计接口：** 前端需要展示"今日已用 X 次 / 剩余 Y 次"，目前没有暴露接口。需要加 `GET /api/usage/today` 返回当日用量。

6. **报告版本管理：** 如果用户对同一家公司多次分析，report 表会有多条记录。是否需要"覆盖旧报告"还是"保留历史版本"？当前是保留所有。

---

## 七、新增/修改文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| Report.java | 新增 | report 表实体 |
| ReportMapper.java | 新增 | report 表 Mapper |
| ReportService.java | 新增 | 报告搜索 + 详情 |
| ReportVO.java | 新增 | 报告返回 VO |
| ReportController.java | 新增 | GET /api/reports/search + /{id} |
| AiUsageDaily.java | 新增 | ai_usage_daily 表实体 |
| AiUsageDailyMapper.java | 新增 | 每日用量 Mapper |
| AiUsageService.java | 新增 | 限额检查 + 用量累加 |
| AnalysisTaskListVO.java | 新增 | 列表简化 VO（不含 result） |
| PipelineExecutionService.java | 修改 | 加 report 写入 + 用量累加 |
| AnalysisTaskService.java | 修改 | 加限额检查 + listTasks 返回 ListVO |
| TaskController.java | 修改 | 列表返回类型改为 IPage<AnalysisTaskListVO> |
