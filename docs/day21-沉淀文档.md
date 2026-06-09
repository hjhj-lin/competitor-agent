# Day 21 沉淀文档 — API限流 + Pipeline重试退避

## 一、今天做了哪些功能

| 操作 | 文件 | 变化 |
|------|------|------|
| 新增 | `annotation/RateLimit.java` | 自定义限流注解，permits + message |
| 新增 | `interceptor/RateLimitInterceptor.java` | Caffeine滑动窗口限流拦截器 |
| 改造 | `config/WebMvcConfig.java` | 注册RateLimitInterceptor |
| 改造 | `controller/TaskController.java` | createTask()加@RateLimit(permits=5) |
| 改造 | `framework/AgentPipeline.java` | executeWithRetry()加指数退避1s→2s→4s |
| 验证 | 限流测试 | 6次请求，前5次200，第6次429 |
| 验证 | TaskId=55（网易） | COMPLETED，Result=11538字节 |

**一句话总结**：给API加限流防恶意刷接口，给重试加退避避免雪崩，模块3（容错加固）全部完成。

---

## 二、关键代码逻辑链路

### 限流：@RateLimit + RateLimitInterceptor

```java
// 注解声明
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int permits() default 5;           // 每分钟允许次数
    String message() default "请求过于频繁";  // 超限提示
}

// Controller使用
@PostMapping
@RateLimit(permits = 5, message = "创建任务过于频繁，每分钟最多5次")
public Result<AnalysisTaskVO> createTask(...) { ... }
```

**拦截器链路**：
```
请求 → JwtInterceptor(解析token,设currentUserId)
     → RateLimitInterceptor(检查@RateLimit注解)
       → 无注解: 放行
       → 有注解: Caffeine Cache查 "userId:方法名" 计数
         → count <= permits: 放行, count+1
         → count > permits: 返回429
     → Controller方法
```

### Caffeine滑动窗口实现

```java
// key: "userId:createTask", value: 请求计数器
private final Cache<String, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
    .expireAfterWrite(1, TimeUnit.MINUTES)  // 1分钟窗口
    .maximumSize(1000)                       // 最多1000个key
    .build();

// 每次请求计数+1
AtomicInteger counter = rateLimitCache.get(key, k -> new AtomicInteger(0));
int currentCount = counter.incrementAndGet();
if (currentCount > rateLimit.permits()) {
    // 超限，返回429
}
```

**为什么用Caffeine而不是Redis**：
- 单机部署，Caffeine内存缓存足够
- 不引入额外依赖（项目已有Caffeine）
- 性能更好（无网络开销）

### 指数退避：executeWithRetry

```java
for (int i = 0; i <= step.getMaxRetries(); i++) {
    try {
        AgentResult result = step.getAgent().execute(context);
        if (result.isSuccess()) return result;
        if (i < step.getMaxRetries()) {
            long backoffMs = (1L << i) * 1000; // 1s, 2s, 4s
            Thread.sleep(backoffMs);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return AgentResult.fail("重试被中断");
    }
}
```

**退避时间表**：
| 重试次数 | 退避时间 | 累计等待 |
|---------|---------|---------|
| retry 1 | 1s | 1s |
| retry 2 | 2s | 3s |
| retry 3 | 4s | 7s |

---

## 三、重要概念的人话解释

### 滑动窗口限流

通俗说：只看最近1分钟内的请求数，更早的不算。就像"最近1分钟内你最多按5次电梯"。

**vs 固定窗口**：固定窗口在窗口边界可能突发2倍流量（第59秒5次+第1秒5次=2秒内10次）。滑动窗口更精确。

**Caffeine实现原理**：`expireAfterWrite(1, MINUTES)` 意味着每个key写入1分钟后自动过期删除。1分钟内同一key的请求共享同一个AtomicInteger计数器。

### 指数退避（Exponential Backoff）

通俗说：重试不是立刻重试，而是越来越慢地重试。就像敲门没人开，先等1秒再敲，还没开就等2秒，再等4秒。

**为什么不用固定间隔**：如果API限流了，固定间隔重试只会持续触发限流。指数退避给服务恢复的时间。

**公式**：`backoff = 2^retryCount * baseInterval`，即 1s → 2s → 4s → 8s → ...

### 拦截器执行顺序

通俗说：Spring拦截器按注册顺序执行，像安检通道，先过第一个安检（JWT），再过第二个（限流）。

**关键**：RateLimitInterceptor 必须在 JwtInterceptor 之后注册，因为限流需要 `currentUserId`，而这个值是 JwtInterceptor 设置的。

---

## 四、联调中踩过的坑

### 坑1：限流拦截器注册顺序

**现象**：如果 RateLimitInterceptor 在 JwtInterceptor 之前注册，`currentUserId` 为 null，限流失效

**解决**：确保 JwtInterceptor 先注册，RateLimitInterceptor 后注册

### 坑2：PowerShell Invoke-WebRequest 遇到429直接抛异常

**现象**：`Invoke-RestMethod` 遇到非2xx状态码抛异常，无法获取响应体

**解决**：用 `try-catch` 捕获异常，从 `$_.Exception.Response` 读取状态码；或用 `Invoke-WebRequest` + `-ErrorAction SilentlyContinue`

### 坑3：Thread.sleep 和 InterruptedException

**现象**：`Thread.sleep()` 可能抛 `InterruptedException`，如果不处理会导致线程中断状态丢失

**解决**：捕获 InterruptedException 后调用 `Thread.currentThread().interrupt()` 恢复中断标志

---

## 五、值得记住的经验

### 1. 自定义注解 + 拦截器 模板

```
Step 1: 定义注解 @RateLimit(permits, message)
Step 2: 实现拦截器 HandlerInterceptor.preHandle()
Step 3: 注册拦截器 WebMvcConfig.addInterceptors()
Step 4: 在Controller方法上加注解
```

这是所有Spring项目都可以复用的限流方案。

### 2. Caffeine Cache 的3个核心配置

| 配置 | 作用 | 本项目值 |
|------|------|---------|
| `expireAfterWrite` | 写入后多久过期 | 1分钟（限流窗口） |
| `maximumSize` | 最大缓存条目数 | 1000（用户数上限） |
| `get(key, mappingFunction)` | 不存在时自动创建 | `new AtomicInteger(0)` |

### 3. 指数退避公式

```java
long backoffMs = (1L << i) * 1000;  // i=0:1s, i=1:2s, i=2:4s
```

用位运算 `1L << i` 比 `Math.pow(2, i)` 更高效，且不会丢失精度。

### 4. 模块3（容错加固）完成清单

| 能力 | 实现方式 | Day |
|------|---------|-----|
| 熔断保护 | Resilience4j @CircuitBreaker | Day 20 |
| API限流 | @RateLimit + Caffeine滑动窗口 | Day 21 |
| 重试退避 | 指数退避 1s→2s→4s | Day 21 |

---

## 六、还不熟、下次还要追问的问题

1. **Caffeine vs Guava Cache？** — 两者区别是什么？为什么选Caffeine？
2. **分布式限流？** — 多实例部署时Caffeine内存缓存不共享，怎么限流？Redis+Lua？
3. **令牌桶 vs 滑动窗口？** — 各自优缺点？什么场景用哪个？
4. **限流粒度？** — 按userId、按IP、按接口？生产环境怎么选？
5. **退避上限？** — 指数退避要不要设上限？比如最多等30s？
6. **退避+抖动？** — 多个客户端同时退避重试会导致"惊群效应"，要不要加随机抖动？
7. **限流和熔断的关系？** — 限流是入口防护，熔断是出口防护，两者如何配合？

---

## 七、Day 22 候选方向

参考 `二次开发总体规划.md`，模块3已完成，进入模块4。

**Day 22 计划**：
- **Prompt外部化**：把4个Agent的system prompt从Java常量抽到数据库
- **Caffeine缓存**：PromptService加缓存，避免每次查库
- **热更新API**：PUT /api/prompts/{agentName} 修改prompt无需重启
