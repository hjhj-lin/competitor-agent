# Day 17 沉淀文档

## 一、今天做了哪些功能

| 操作 | 文件 | 效果 |
|------|------|------|
| 新增 | `framework/BaseReActAgent.java` | 模板方法模式，4个Agent共用执行骨架 |
| 改造 | `framework/CollectAgent.java` | 97行→33行，继承BaseReActAgent |
| 改造 | `framework/AnalyzeAgent.java` | 97行→33行，继承BaseReActAgent |
| 改造 | `framework/ReportAgent.java` | 97行→33行，继承BaseReActAgent |
| 改造 | `framework/ReviewAgent.java` | 97行→33行，继承BaseReActAgent |
| 新增 | `tool/ReadReportTool.java` | AI可读取同用户的历史报告 |
| 修改 | `service/PipelineExecutionService.java` | ThreadLocal传递userId + finally清理 |

**核心成果**：
- 4个Agent从重复代码变为模板方法+3个抽象方法，新增Agent只需15行业务代码
- AI现在有2个工具：searchWeb（搜索）+ readHistoryReport（读历史报告）
- 全链路验证通过：登录→创建→4Agent串行→COMPLETED→报告搜索

---

## 二、关键代码逻辑链路

### 链路1：BaseReActAgent模板方法

```
BaseReActAgent.execute(context)  ← 模板方法，所有Agent共用
  │
  ├─ buildQuestion(context)      ← 子类实现：构建问题（可能从上游取数据）
  ├─ chatClient.prompt()
  │     .system(getSystemPrompt())    ← 子类实现：角色指令
  │     .user(question)
  │     .tools(searchTools, readReportTool)  ← 注册2个工具
  │     .call().content()
  ├─ agentExecutionMapper.insert()    ← 记录执行过程
  └─ AgentResult.success(Map.of(
        "companyName", companyName,
        getResultKey(), result))      ← 子类实现：结果key名
```

### 链路2：Agent间数据传递

```
CollectAgent: buildQuestion() → 直接用context.getCompanyName()
AnalyzeAgent: buildQuestion() → getUpstreamResult(context, "collect", "collectResult", fallback)
ReportAgent:  buildQuestion() → getUpstreamResult(context, "analyze", "analysisResult", fallback)
ReviewAgent:  buildQuestion() → getUpstreamResult(context, "report", "reportResult", fallback)
```

### 链路3：ReadReportTool + ThreadLocal

```
PipelineExecutionService.executePipeline():
  ReadReportTool.setUserId(userId)  ← 开始时设置
  try {
    agentPipeline.execute(context)   ← AI可能调用readHistoryReport()
  } finally {
    ReadReportTool.clearUserId()     ← 结束时清理，防内存泄漏
  }
```

---

## 三、重要概念的人话解释

### 模板方法模式（Template Method）
**人话**：父类写好"先做A再做B最后做C"的固定流程，子类只填"A具体做什么、B具体做什么"。
**专业**：定义算法骨架，将某些步骤延迟到子类实现，不改变算法结构的前提下重新定义某些步骤。

### ThreadLocal
**人话**：每个线程有一个私有小盒子，往里放东西只有自己线程能拿到，别的线程看不到。
**专业**：线程本地变量，为每个线程提供独立的变量副本，避免线程间共享数据的并发问题。

### @Tool注解
**人话**：给AI贴个标签说"你有这个能力可以用"，AI自己决定什么时候用。
**专业**：Spring AI的Tool Calling声明注解，框架自动生成JSON Schema注册到LLM的tools参数中。

### 模板方法 vs 策略模式
**人话**：模板方法是"流程固定，步骤可变"；策略模式是"整个算法可替换"。
**专业**：模板方法用继承+抽象方法扩展，策略模式用组合+接口替换。这里选模板方法因为4个Agent的执行流程完全一致。

---

## 四、联调中踩过的坑

### 坑1：Maven编译Lombok失效
**现象**：`mvn compile` 报大量"找不到符号"错误，所有@Data/@Slf4j注解不生效
**原因**：git stash pop后target目录缓存损坏，加上pom.xml中Lombok的optional=true导致Maven命令行编译时注解处理器不生效
**解决**：`git checkout -- .` 恢复所有文件，`Remove-Item -Recurse -Force target` 清理缓存，重新编译
**教训**：git stash pop可能导致文件状态不一致，编译失败时先尝试clean

### 坑2：ReviewAgent缺少ReadReportTool的import
**现象**：编译报错 `找不到符号 ReadReportTool`
**原因**：Edit工具超时，import语句没有实际写入文件
**解决**：手动重新添加import
**教训**：Edit超时后必须验证文件实际内容，不能假设写入成功

### 坑3：运行时ClassNotFoundException: ReadReportTool
**现象**：编译通过但运行时找不到ReadReportTool类
**原因**：target目录中旧的class文件缓存，新增的类没有被编译进去
**解决**：`Remove-Item -Recurse -Force target` 后重新 `mvn compile`
**教训**：新增文件后如果运行时报ClassNotFoundException，先清理target目录

---

## 五、值得记住的经验

### 1. 模板方法模式的识别信号
当你发现多个类的代码结构完全一致，只有3-5个差异点时，就是模板方法的信号：
- 找出不变的部分 → 放到父类的模板方法中
- 找出变化的部分 → 定义为抽象方法
- 提供工具方法（如getUpstreamResult）减少子类重复

### 2. ThreadLocal的正确使用姿势
```java
// 设置
ThreadLocal.set(value);
try {
    // 业务逻辑（可能被其他组件读取）
} finally {
    ThreadLocal.remove();  // 必须清理！否则内存泄漏
}
```
关键点：finally中必须清理，否则线程池复用时数据串线程。

### 3. @Tool方法的参数设计原则
- 参数必须是LLM能推断出来的（如companyName，AI从问题中知道）
- 不能传userId这种LLM不知道的值 → 用ThreadLocal传递
- description要写清楚工具干什么、参数什么意思，AI根据描述决定是否调用

### 4. 代码精读要抓分层
今天的5个文件形成清晰的4层结构：
```
工厂层: SpringAiConfig → 创建ChatClient
业务层: BaseReActAgent → 模板方法+抽象方法
声明层: SearchTools + ReadReportTool → @Tool注册
实现层: TavilyService → 实际搜索逻辑
```
每层只做一件事，改一层不影响其他层。

---

## 六、还不熟、下次还要追问的问题

1. **Spring AI Tool Calling的最大循环次数**：AI调用工具→观察结果→继续推理，最多循环几次？能配置吗？
2. **多个@Tool时AI的选择策略**：AI怎么决定用searchWeb还是readHistoryReport？description的写法对选择有多大影响？
3. **ThreadLocal + 线程池的风险**：如果pipelineExecutor线程池的线程被复用，ThreadLocal没清理会怎样？Spring的@Async线程池有特殊处理吗？
4. **模板方法 vs 函数式接口**：如果用函数式接口（Supplier/Function）替代继承，代码会更简洁吗？各有什么优劣？
5. **ReadReportTool截取2000字够吗**：如果历史报告很长，截取前2000字可能丢失关键信息。有没有更好的策略（如摘要、分段）？
6. **BaseReActAgent的tools注册方式**：目前硬编码了searchTools和readReportTool，如果未来有10个工具怎么办？能否动态注册？
7. **Maven编译Lombok的optional问题**：为什么optional=true时Maven命令行编译不生效，但IDE编译正常？Spring Boot parent POM是怎么处理这个的？
