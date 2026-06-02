# Day7 沉淀文档 — Agent 框架接口 + 最简 Agent

## 一、今天做了哪些功能

| 步骤 | 内容 | 验证结果 |
|------|------|----------|
| 7-1 | 定义 Agent/AgentContext/AgentResult 核心接口 | 编译通过 |
| 7-2 | 实现最简 CollectAgent（不循环，调一次 AI） | 编译通过 |
| 7-3 | Agent 接入任务创建流程 | 创建任务后 Agent 自动执行，result 有内容 |
| 7-4 | agent_execution 表 + 执行记录落库 | 执行记录写入数据库，有耗时和 AI 调用次数 |

## 二、关键代码逻辑链路

### 创建任务 + Agent 执行的完整链路

```
前端 POST /api/tasks + {"companyName":"Microsoft"}
    ↓
JwtInterceptor → 解析 token → currentUserId=4
    ↓
TaskController.createTask(userId, request)
    ↓
AnalysisTaskService.createTask()
    → 1. 组装 task（status=RUNNING, currentAgent="collect"）
    → 2. insert(task) ← 先入库
    → 3. AgentContext.of(taskId, companyName) ← 构建上下文
    → 4. collectAgent.execute(context) ← 调 Agent
    ↓
CollectAgent.execute(context)
    → 1. 创建 AgentExecution 记录
    → 2. 拼提示词（"你是一个竞品信息采集专家..."）
    → 3. aiService.chat(prompt) ← 调 DeepSeek
    → 4. 成功/失败都 insert(execution) ← 执行记录落库
    → 5. 返回 AgentResult
    ↓
AnalysisTaskService 拿到 AgentResult
    → 成功：task.status=COMPLETED, task.result=AI回复
    → 失败：task.status=FAILED, task.result="Agent执行失败: xxx"
    → updateById(task) ← 更新任务最终状态
    ↓
返回 Result.success(AnalysisTaskVO)
```

### Agent 框架的 3 个核心类

```
Agent（接口）
    → getName()：Agent 名字，比如 "collect"
    → getDescription()：Agent 描述
    → execute(AgentContext)：执行方法，返回 AgentResult

AgentContext（上下文）
    → taskId：关联的任务 ID
    → companyName：要分析的公司名
    → inputs/outputs：扩展用的 Map，将来传工具结果

AgentResult（结果）
    → success：是否成功
    → data：结果数据（Map<String, Object>）
    → error：失败原因
    → confidence：置信度（将来用）
```

### CollectAgent 的执行流程

```
execute(context)
    → 创建 AgentExecution 记录（先不插入，等执行完再插）
    → 拼提示词 → aiService.chat(prompt)
    → 成功：
        execution.status=COMPLETED
        execution.outputData=AI回复
        execution.durationMs=耗时
        execution.aiCallCount=1
        insert(execution) ← 落库
        return AgentResult.success(data)
    → 失败：
        execution.status=FAILED
        execution.errorMessage=错误信息
        execution.durationMs=耗时
        insert(execution) ← 落库
        return AgentResult.fail(error)
```

## 三、重要概念的人话解释

### 1. Agent 接口 — 为什么要定义接口？
- 人话：接口就是"合同"，规定了 Agent 必须有哪些能力
- 好处：将来加新 Agent（AnalyzeAgent、ReportAgent），只需要 implements Agent，不用改旧代码
- 这叫"面向接口编程"，是 Spring 依赖注入的基础

### 2. AgentContext — 为什么不直接传参数？
- 人话：如果 Agent 只需要 companyName，直接传 String 就行。但将来 Agent 可能需要很多输入（任务 ID、用户偏好、前一个 Agent 的输出），一个个传参数会爆炸
- 用 Context 对象封装所有输入，加新字段不用改方法签名
- 这叫"上下文模式"，跟 HttpServletRequest 是同一个思路

### 3. AgentResult — 为什么用 Map<String, Object>？
- 人话：不同 Agent 返回的数据结构不一样。采集 Agent 返回 collectResult，分析 Agent 返回 analyzeResult
- 用 Map 可以灵活放任何数据，不用为每个 Agent 定义一个 Result 类
- 将来可以换成更规范的 VO

### 4. agent_execution 表 — 为什么要单独记录？
- 人话：analysis_task 记录的是"任务最终状态"，agent_execution 记录的是"每一步执行过程"
- 一个任务可能经过多个 Agent（collect → analyze → report），每个 Agent 都有一条执行记录
- 将来排查问题时，看 agent_execution 就知道哪个 Agent 慢、哪个 Agent 失败了

### 5. 为什么用 framework 包名而不是 agent 包名？
- 因为项目根包是 com.competitor.agent，如果子包也叫 agent，Java 编译器会混淆包名和类名
- framework 更准确：Agent 接口是框架代码，CollectAgent 是业务代码

## 四、联调中踩过的坑

### 坑1：PowerShell Set-Content 写文件带 BOM
- 现象：编译报错 "非法字符: '\ufeff'"
- 原因：PowerShell 的 Set-Content -Encoding UTF8 会写 BOM 头（字节顺序标记）
- 解决：用 `[System.IO.File]::WriteAllText(path, content, $utf8NoBom)`，其中 `$utf8NoBom = New-Object System.Text.UTF8Encoding $false`
- 教训：在 Windows 上写 Java 源文件，必须用无 BOM 的 UTF-8

### 坑2：Java 字符串不能跨行
- 现象：编译报错 "未结束的字符串文字"
- 原因：Java 字符串字面量不能直接换行，必须用 `\n` 或 `+` 拼接
- 解决：每行用 `+` 连接，末尾加 `\n`

### 坑3：mvn compile 报大量 Lombok 错误
- 现象：找不到 getter/setter 方法
- 原因：target 目录缓存了旧的编译结果
- 解决：用 `mvn clean compile` 而不是 `mvn compile`
- 教训：遇到莫名其妙的编译错误，先 clean 再 compile

## 五、值得记住的经验

### 1. 先定义接口，再实现
Agent 接口只有 3 个方法，但有了它，AnalysisTaskService 依赖的是 Agent 接口而不是 CollectAgent 具体实现。将来换 Agent，Service 不用改。

### 2. Agent 执行记录要"成功失败都落库"
不管 Agent 成功还是失败，都 insert(execution)。这样 agent_execution 表是完整的执行日志，不会因为失败就丢记录。

### 3. 执行耗时要在 Agent 内部记录
durationMs 在 CollectAgent 里计算，不在 Service 里。因为将来可能有多个 Agent，每个 Agent 的耗时要分别记录。

## 六、还不熟、下次还要追问的问题

1. 当前 CollectAgent 是直接注入的，将来怎么根据任务类型动态选择 Agent？
   - 提示：可以用 Map<String, Agent>，Spring 会自动把所有 Agent 实现注入进来，key 是 Bean 名

2. Agent 执行是同步的，创建任务要等 10 秒。怎么改成异步？
   - 提示：@Async + CompletableFuture，或者用 Spring 的事件机制

3. AgentResult 用 Map<String, Object> 不够类型安全，怎么改进？
   - 提示：可以定义泛型 AgentResult<T>，或者用 Jackson 转成具体 VO

4. agent_execution 的 inputData/outputData 是 TEXT 类型，将来数据量大了会不会有性能问题？
   - 提示：可以加索引、分表，或者把大字段存到文件/对象存储

5. CollectAgent 里的提示词是硬编码的，怎么管理？
   - 提示：可以放到配置文件或数据库，做成"提示词模板"
