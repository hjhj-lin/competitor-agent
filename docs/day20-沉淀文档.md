# Day 20 沉淀文档 — Resilience4j 熔断保护

## 一、今天做了哪些功能

| 操作 | 文件 | 变化 |
|------|------|------|
| 新增 | `pom.xml` | 加 resilience4j-spring-boot3 + resilience4j-circuitbreaker 依赖 |
| 新增 | `application.yml` | 配置 tavily + aiCall 两个熔断器实例 |
| 改造 | `TavilyService.java` | `search()` 加 `@CircuitBreaker(name="tavily")`，fallback 走 DeepSeek |
| 改造 | `BaseReActAgent.java` | `execute()` 加 `@CircuitBreaker(name="aiCall")`，fallback 返回失败 |
| 验证 | TaskId=47（拼多多） | COMPLETED，Result=10862字节 |
| 验证 | TaskId=48（小米） | COMPLETED，Result=11021字节 |

**一句话总结**：给外部API调用加熔断器，连续失败时自动降级，防止故障级联拖垮系统。

---

## 二、关键代码逻辑链路

### 熔断器1：TavilyService.search()

```java
@CircuitBreaker(name = "tavily", fallbackMethod = "searchFallback")
public String search(String query) {
    if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
        return searchWithTavily(query);  // 异常向上抛给熔断器
    }
    return searchWithDeepSeek(query);
}

private String searchFallback(String query, Exception e) {
    log.warn("[熔断降级] Tavily熔断，使用DeepSeek搜索 error={}", e.getMessage());
    return searchWithDeepSeek(query);  // 降级到DeepSeek
}
```

**链路**：
```
search() → searchWithTavily()
  成功 → 熔断器记录成功 → 返回结果
  失败(抛RuntimeException) → 熔断器记录失败
    失败率 < 50% → 继续调Tavily
    失败率 >= 50% → 熔断开启 → searchFallback() → DeepSeek
```

### 熔断器2：BaseReActAgent.execute()

```java
@Override
@CircuitBreaker(name = "aiCall", fallbackMethod = "executeFallback")
public AgentResult execute(AgentContext context) {
    // ... chatClient.stream() 调用
}

private AgentResult executeFallback(AgentContext context, Exception e) {
    log.warn("[AI熔断降级] agent={} error={}", getName(), e.getMessage());
    return AgentResult.fail("AI服务暂不可用: " + e.getMessage());
}
```

**链路**：
```
Pipeline → Agent.execute()
  成功 → 熔断器记录成功 → AgentResult.success()
  失败 → 熔断器记录失败
    失败率 < 50% → 继续调AI
    失败率 >= 50% → 熔断开启 → executeFallback() → AgentResult.fail()
```

### 熔断器状态机

```
CLOSED（正常）→ 失败率>=50% → OPEN（熔断）
OPEN → 等待30s/60s → HALF_OPEN（半开）
HALF_OPEN → 放3个请求试探
  成功 → CLOSED
  失败 → OPEN
```

---

## 三、重要概念的人话解释

### 熔断器（Circuit Breaker）

通俗说：就像家里的保险丝。电流过大时保险丝断开，保护电器不被烧坏。等故障排除后，手动恢复供电。

**三种状态**：
- **CLOSED（关闭）**：正常工作，所有请求都通过，同时统计失败率
- **OPEN（开启）**：熔断了，所有请求直接走降级逻辑，不调用外部API
- **HALF_OPEN（半开）**：试探状态，放少量请求过去测试，成功就恢复，失败继续熔断

### 滑动窗口（Sliding Window）

通俗说：只看最近N次调用的结果，更早的不管。就像"最近10次考试，5次不及格就补考"。

- `sliding-window-size: 10` — 统计最近10次
- `failure-rate-threshold: 50` — 失败率超过50%触发熔断
- `minimum-number-of-calls: 5` — 至少5次调用才开始统计（避免样本太少误判）

### @CircuitBreaker 注解

通俗说：给方法加个"保镖"，方法正常时保镖不干预；方法连续出错时，保镖拦住后续请求，直接走备用方案。

**关键限制**：
- 只能加在 **public** 方法上（Spring AOP 代理要求）
- 加在 **private** 方法上不生效（代理对象调不到 private）
- fallback 方法签名必须和原方法一致，额外加一个 `Exception` 参数

### Checked Exception vs Unchecked Exception

通俗说：
- **Checked**（如 `IOException`）：编译器强制你处理，要么 try-catch 要么 throws
- **Unchecked**（如 `RuntimeException`）：编译器不管，运行时才报错

Resilience4j 熔断器只统计 **RuntimeException**（unchecked）。如果方法抛 checked exception，需要包装成 RuntimeException。

---

## 四、联调中踩过的坑

### 坑1：@CircuitBreaker 加在 private 方法上不生效

**现象**：第一次把 `@CircuitBreaker` 加在 `searchWithTavily()`（private）上，熔断器不工作

**原因**：Spring AOP 用 CGLIB 代理，代理对象只能拦截 public 方法

**解决**：把 `@CircuitBreaker` 移到 public 的 `search()` 方法上

### 坑2：searchWithTavily 声明 throws Exception 导致编译失败

**现象**：`"Unhandled exception type Exception"`，TaskId=46 FAILED

**原因**：`searchWithTavily()` 声明 `throws Exception`（checked），`search()` 调用它但没有 try-catch 或 throws 声明

**解决**：`searchWithTavily()` 内部 try-catch，checked exception 包装成 RuntimeException 向上抛

```java
private String searchWithTavily(String query) {
    try {
        // ... HTTP调用
        throw new RuntimeException("Tavily API返回" + statusCode);  // 非成功状态码
    } catch (RuntimeException e) {
        throw e;  // RuntimeException直接向上抛，让熔断器统计
    } catch (Exception e) {
        throw new RuntimeException("Tavily搜索异常: " + e.getMessage(), e);  // checked包装成unchecked
    }
}
```

### 坑3：Maven target 缓存

**现象**：代码改了但运行还是旧逻辑

**解决**：`Remove-Item -Recurse -Force target` 后重新 `mvn compile`

---

## 五、值得记住的经验

### 1. Resilience4j 熔断器3步模板

```java
// Step 1: pom.xml 加依赖
// resilience4j-spring-boot3 + resilience4j-circuitbreaker

// Step 2: application.yml 配置实例
// resilience4j.circuitbreaker.instances.xxx

// Step 3: 方法上加注解
@CircuitBreaker(name = "xxx", fallbackMethod = "xxxFallback")
public Result doSomething() { ... }

private Result xxxFallback(Exception e) { ... }
```

### 2. 熔断器命名规范

| 名称 | 保护对象 | 窗口大小 | 等待时间 |
|------|---------|---------|---------|
| `tavily` | Tavily API搜索 | 10次 | 30s |
| `aiCall` | AI模型调用 | 6次 | 60s |

**原则**：不同外部依赖用不同熔断器，避免一个API故障影响另一个。

### 3. fallback 方法签名规则

```java
// 原方法
public String search(String query)

// fallback 必须参数一致 + 额外 Exception 参数
private String searchFallback(String query, Exception e)
```

**参数不匹配会报错**：`NoSuchMethodException`

### 4. 异常传播原则

- **要被熔断器统计的异常**：必须向上抛（RuntimeException）
- **不想被统计的异常**：内部消化（try-catch 处理掉）
- **checked exception**：必须包装成 RuntimeException 才能被熔断器捕获

---

## 六、还不熟、下次还要追问的问题

1. **Resilience4j 的 actuator 端点？** — 怎么通过 /actuator/circuitbreakers 查看熔断器状态？
2. **熔断器事件监听？** — 怎么在熔断开启时发告警通知？
3. **@CircuitBreaker 和 @Retry 能否叠加？** — 先重试3次，再熔断？
4. **熔断器指标接入 Prometheus？** — 怎么把熔断器的成功/失败率接入监控？
5. **fallback 方法能是 private 吗？** — 当前用的 private，Spring AOP 能调到吗？
6. **多实例部署时熔断器状态共享？** — 单机内存状态，多实例怎么同步？
7. **熔断器配置热更新？** — 改了 yml 后需要重启吗？还是能动态生效？

---

## 七、Day 21 候选方向

参考 `二次开发总体规划.md`，Day 20 已完成模块3的熔断部分。

**Day 21 计划**：
- **API限流**：基于 Caffeine 滑动窗口，给 TaskController.createTask() 加限流
- **Pipeline重试退避**：AgentPipeline 重试加入指数退避间隔（1s→2s→4s）
