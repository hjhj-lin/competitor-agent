# Day 22 沉淀文档 — Prompt外部化

## 一、今天做了哪些功能

| 操作 | 文件 | 变化 |
|------|------|------|
| 新增 | `entity/PromptTemplate.java` | prompt_template表实体 |
| 新增 | `mapper/PromptTemplateMapper.java` | MyBatis-Plus Mapper |
| 新增 | `service/PromptService.java` | Prompt读取 + Caffeine缓存 + 热更新 |
| 新增 | `controller/PromptController.java` | GET/PUT /api/prompts |
| 改造 | `framework/BaseReActAgent.java` | getSystemPrompt()改为从DB读取 |
| 改造 | `framework/CollectAgent.java` | 删除硬编码常量，构造函数加PromptService |
| 改造 | `framework/AnalyzeAgent.java` | 同上 |
| 改造 | `framework/ReportAgent.java` | 同上 |
| 改造 | `framework/ReviewAgent.java` | 同上 |
| 新增 | `docs/init_prompt_template.sql` | 建表+初始数据SQL |
| 验证 | TaskId=56（京东） | COMPLETED，Result=12118字节 |
| 验证 | 热更新API | collect v1→v2→v3，缓存自动失效 |

**一句话总结**：4个Agent的system prompt从Java硬编码常量迁移到数据库，支持Caffeine缓存加速读取 + REST API热更新无需重启。

---

## 二、关键代码逻辑链路

### Prompt外部化完整链路

```
Agent执行 → BaseReActAgent.execute()
  → getSystemPrompt()
    → promptService.getSystemPrompt("collect")
      → Caffeine缓存命中? → 直接返回
      → 缓存未命中 → promptTemplateMapper.selectOne(agentName)
        → 写入缓存（5分钟过期）→ 返回
  → chatClient.prompt().system(prompt).user(question).stream()...
```

### 热更新链路

```
PUT /api/prompts/collect {systemPrompt: "...", userPromptTemplate: "..."}
  → PromptController.updatePrompt()
    → PromptService.updatePrompt()
      → DB UPDATE prompt_template SET ... version=version+1
      → evictCache("collect")  ← 清除Caffeine缓存
    → 返回更新后的PromptTemplate
  → 下次Agent执行时，缓存未命中，从DB读取新prompt
```

### 关键代码：BaseReActAgent.getSystemPrompt() 改造

```java
// 改造前：抽象方法，子类硬编码
protected abstract String getSystemPrompt();

// 改造后：从PromptService读取（数据库配置）
protected String getSystemPrompt() {
    return promptService.getSystemPrompt(getName());
}
```

### 关键代码：PromptService缓存机制

```java
private final Cache<String, PromptTemplate> promptCache = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)  // 5分钟过期
    .maximumSize(100)                        // 最多100个key
    .build();

public String getSystemPrompt(String agentName) {
    PromptTemplate template = promptCache.get(agentName, this::loadFromDb);
    return template.getSystemPrompt();
}

public PromptTemplate updatePrompt(String agentName, String systemPrompt, String userPromptTemplate) {
    // 1. 更新DB
    template.setSystemPrompt(systemPrompt);
    template.setVersion(template.getVersion() + 1);
    promptTemplateMapper.updateById(template);
    // 2. 清缓存
    evictCache(agentName);
    return template;
}
```

---

## 三、重要概念的人话解释

### Prompt外部化

通俗说：把AI的"人设"从代码里搬出来，放到数据库里。就像把员工手册从刻在墙上变成活页夹，随时可以抽换。

**好处**：
- 调优prompt不需要改代码→编译→部署
- 产品经理可以直接调API改prompt
- 不同环境（dev/staging/prod）可以用不同prompt

### Caffeine Cache的"读穿透"模式

通俗说：先查缓存，没有再查数据库，查到后写回缓存。就像"先看冰箱有没有，没有再去超市买，买完放冰箱"。

```java
promptCache.get(key, mappingFunction)
// 等价于：
// if (cache.has(key)) return cache.get(key);
// else { value = loadFromDb(key); cache.put(key, value); return value; }
```

### 缓存一致性：更新时清缓存

通俗说：改了数据库后，把冰箱里的旧货扔掉，下次有人来拿就会去超市买新的。

**为什么不用"更新缓存"**：更新缓存可能导致并发场景下缓存和DB不一致。清缓存（Cache Invalidation）更简单安全。

### version字段的作用

每次更新prompt时version+1，作用：
1. **审计**：知道prompt被改了几次
2. **排查**：日志里看到version=3，知道用的是第3版prompt
3. **乐观锁**：未来可加@Version注解防并发冲突

---

## 四、联调中踩过的坑

### 坑1：PowerShell + MySQL中文编码

**现象**：`mysql -e "INSERT..."` 插入中文报 `Incorrect string value`

**原因**：PowerShell默认编码不是UTF-8，MySQL客户端收到的字节不是UTF-8

**解决**：加 `--default-character-set=utf8mb4` 参数，或用SQL文件 + source命令

### 坑2：PowerShell不支持 `<` 重定向

**现象**：`mysql < file.sql` 报 "运算符是为将来使用而保留的"

**原因**：PowerShell的 `<` 不是输入重定向

**解决**：用 `mysql -e "source file.sql"` 代替

### 坑3：4个Agent构造函数都要改

**现象**：BaseReActAgent加了PromptService参数，4个子类构造函数都要加

**解决**：逐个修改CollectAgent/AnalyzeAgent/ReportAgent/ReviewAgent，Spring自动注入PromptService

---

## 五、值得记住的经验

### 1. "配置外部化"三步模板

```
Step 1: 建表 + Entity + Mapper（数据层）
Step 2: Service + 缓存（业务层）
Step 3: Controller API（接口层）
```

这个模板适用于所有"从硬编码→可配置"的改造：prompt、阈值、模板等。

### 2. Caffeine Cache三要素

| 配置 | 作用 | 本项目值 |
|------|------|---------|
| `expireAfterWrite` | 写入后多久过期 | 5分钟 |
| `maximumSize` | 最大缓存条目数 | 100 |
| `get(key, loader)` | 不存在时自动加载 | `loadFromDb()` |

### 3. 缓存一致性策略选择

| 策略 | 适用场景 | 复杂度 |
|------|---------|--------|
| Cache Invalidation（清缓存） | 写少读多 | 低 |
| Cache Aside（更新缓存） | 强一致性要求 | 中 |
| Write Through（同步写缓存+DB） | 写多读多 | 高 |

本项目用Cache Invalidation，因为prompt更新频率很低。

### 4. 子类构造函数变更模式

当父类构造函数加参数时，所有子类都要改：
1. 子类构造函数加参数
2. `super()` 调用加参数
3. Spring自动注入会处理依赖

---

## 六、还不熟、下次还要追问的问题

1. **Prompt版本回滚？** — 当前只有version递增，没有回滚机制。要不要加prompt_version_history表？
2. **Prompt A/B测试？** — 不同用户用不同版本的prompt，对比效果？
3. **多模型Prompt适配？** — 不同模型（DeepSeek/GPT/Claude）的prompt格式不同，怎么适配？
4. **Prompt注入攻击？** — 用户输入的companyName可能被拼入prompt，如何防护？
5. **缓存击穿？** — 高并发下缓存过期的瞬间，大量请求同时查DB，如何防护？
6. **Caffeine vs Redis缓存？** — 什么时候该用分布式缓存？
7. **Prompt模板引擎？** — 当前用简单的`{companyName}`占位符，要不要用Thymeleaf/FreeMarker？

---

## 七、Day 23 候选方向

参考 `二次开发总体规划.md`，模块4的Prompt外部化已完成，下一步是多模型支持。

**Day 23 计划**：
- **多模型配置**：application.yml配置多个模型（deepseek-v4-flash + deepseek-chat）
- **模型选择API**：创建任务时可指定模型
- **PipelineConfig动态模型**：不同Agent可用不同模型（如采集用flash，报告用chat）
