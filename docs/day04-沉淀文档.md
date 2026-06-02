# Day 4 沉淀文档 —— 全局异常处理 + 统一响应格式 + 结构化日志

日期：2026-05-26

---

## 一、今天做了哪些功能

| 序号 | 功能 | 验证方式 | 结果 |
|------|------|----------|------|
| 1 | 统一响应类 `Result<T>` | 编译通过 | ✅ |
| 2 | 所有 Controller 返回值改成 `Result<T>` | GET /api/hello 返回 `{code:200, message:"success", data:{...}}` | ✅ |
| 3 | 自定义业务异常 `BusinessException` | 注册重复用户名返回 `{code:500, message:"用户名已存在"}` | ✅ |
| 4 | 全局异常处理器 `GlobalExceptionHandler` | 业务异常/参数校验/兜底都能捕获 | ✅ |
| 5 | 拦截器改用统一响应格式 | 不带 token 返回 `{code:401, message:"未登录", data:null}` | ✅ |
| 6 | 参数校验 `@Valid` + `@NotBlank` | 不传用户名返回 `{code:400, message:"username: 用户名不能为空"}` | ✅ |
| 7 | 结构化日志 `[标签] key=value` | 控制台看到 `[用户注册]`、`[鉴权通过]` 等格式日志 | ✅ |

---

## 二、关键代码逻辑链路

### 正常请求链路

```
GET /api/hello
    ↓
Controller 返回 Result.success(data)
    ↓
Spring 自动序列化为 {"code":200, "message":"success", "data":{...}}
```

### 业务异常链路

```
POST /api/auth/register（重复用户名）
    ↓
Service: throw new BusinessException("用户名已存在")
    ↓
GlobalExceptionHandler 捕获 BusinessException
    ↓
返回 Result.error(500, "用户名已存在")
    ↓
Spring 序列化为 {"code":500, "message":"用户名已存在", "data":null}
```

### 参数校验失败链路

```
POST /api/auth/register（username 为空）
    ↓
@Valid 触发校验 → MethodArgumentNotValidException
    ↓
GlobalExceptionHandler 捕获 → 提取字段名+错误信息
    ↓
返回 Result.error(400, "username: 用户名不能为空")
    ↓
Spring 序列化为 {"code":400, "message":"username: 用户名不能为空", "data":null}
```

### 鉴权失败链路

```
GET /api/users（不带 token）
    ↓
JwtInterceptor: 没找到 Authorization header
    ↓
writeResponse(response, Result.error(401, "未登录，请先登录"))
    ↓
HTTP 200 + {"code":401, "message":"未登录，请先登录", "data":null}
```

### 异常匹配优先级

```
异常抛出
    ↓
是 BusinessException 吗？ → 是 → handleBusinessException（code=500）
    ↓ 否
是 MethodArgumentNotValidException 吗？ → 是 → handleValidationException（code=400）
    ↓ 否
交给兜底 handleException（code=500, message="系统繁忙"）
```

### 涉及的关键文件

| 文件 | 职责 | 一句话理解 |
|------|------|-----------|
| `Result.java` | 统一响应格式 | 模具，所有接口返回值都按这个形状 |
| `BusinessException.java` | 业务异常 | 信封，装业务错误信息 |
| `GlobalExceptionHandler.java` | 全局异常捕获 | 收件箱，所有异常都到这来处理 |
| `JwtInterceptor.java` | JWT 拦截器（改用统一格式） | 安检员，查通行证，不通过也用 Result 格式返回 |

---

## 三、重要概念的人话解释

### Result<T> 统一响应
- 人话：所有接口返回值的"模具"。不管成功失败，都按 `{code, message, data}` 这个形状返回
- 构造函数 private：外部不能 `new Result()`，只能用 `Result.success()` 或 `Result.error()`，保证格式统一
- 泛型 `<T>`：data 可以是任何类型。`Result<List<SysUser>>`、`Result<Map>`、`Result<?>`（不关心类型时用问号）

### @RestControllerAdvice
- 人话：全局异常的"收件箱"。Controller 里抛的任何异常，都会被这个类接住
- 等于 `@ControllerAdvice` + `@ResponseBody`，捕获异常后自动转 JSON
- 不加这个注解的话，异常会直接返回 500 + 一堆 HTML 错误页面

### @ExceptionHandler
- 人话：指定这个方法处理哪种异常。`@ExceptionHandler(BusinessException.class)` = 只接 BusinessException
- 匹配规则：精确匹配优先。BusinessException 比 Exception 更精确，所以业务异常不会进兜底 handler

### BusinessException
- 人话：业务异常的"信封"。装着错误码和错误信息，从 Service 层抛出，被 GlobalExceptionHandler 接住
- 继承 RuntimeException（非受检异常）：不需要 try-catch，也不需要在方法签名上声明 throws
- 为什么不用 Exception：受检异常强制 try-catch，代码会很啰嗦

### @Valid + @NotBlank
- 人话：参数校验注解。`@NotBlank(message="用户名不能为空")` 告诉 Spring：这个字段不能为空，为空就报错
- `@Valid` 加在 Controller 参数上，触发校验。不加的话 `@NotBlank` 不生效
- 校验失败抛 `MethodArgumentNotValidException`，被 GlobalExceptionHandler 接住

### HTTP 状态码 vs 业务状态码
- 人话：HTTP 状态码是给浏览器看的，业务状态码是给前端 JS 看的
- 我们的设计：HTTP 状态码始终 200，业务状态码放在 JSON 的 code 字段里
- 好处：前端只需要解析 JSON 的 code，不用同时处理 HTTP 状态码和 JSON code 两套逻辑
- 比如：没登录时返回 HTTP 200 + `{"code":401, "message":"未登录"}`，前端看到 code=401 就跳登录页

### 结构化日志
- 人话：用 `[标签] key=value` 格式打日志，方便搜索
- 示例：`[用户注册] userId=2 username=zhangsan`、`[鉴权失败] uri=/api/users reason=缺少token`
- 好处：用 `grep "[用户注册]"` 就能过滤出所有注册日志，比在大量日志里肉眼找快得多

---

## 四、联调中踩过的坑

### 坑1：拦截器里手写 JSON 字符串容易出错
- 现象：之前 `response.getWriter().write("{\"code\":401,...}")` 手写 JSON，容易拼错引号
- 解决：改用 `ObjectMapper.writeValueAsString(result)`，Jackson 自动序列化，不会拼错、中文不乱码

### 坑2：参数校验注解不生效
- 原因：Controller 参数上没加 `@Valid`，只有 DTO 上的 `@NotBlank` 不够
- 教训：`@NotBlank` 是规则，`@Valid` 是开关。规则写了不开开关，不生效

### 坑3：PowerShell 显示中文乱码
- 现象：接口返回的中文在 PowerShell 里显示乱码
- 原因：PowerShell 默认编码不是 UTF-8
- 不影响：浏览器和前端 JS 里显示正常，只是命令行工具显示有问题

---

## 五、值得记住的经验

1. **统一响应格式从第一天就该做**：越早做，后面改的越少。Day 1 就应该做，但当时还没讲到。以后新项目，第一步就建 Result 类

2. **全局异常处理器是后端项目的标配**：没有它，一个空指针异常就会返回 500 + 一堆 HTML，用户看到一脸懵。有了它，所有异常都变成友好的 JSON

3. **业务异常和系统异常要分开处理**：BusinessException 返回具体错误信息（"用户名已存在"），Exception 兜底返回模糊信息（"系统繁忙"）。不能把内部错误暴露给用户

4. **HTTP 状态码始终 200，业务状态码放 JSON**：前端只需要一套解析逻辑，不用同时处理 HTTP 状态码和 JSON code

5. **构造函数 private + 静态工厂方法**：控制对象的创建方式，保证格式统一。这是"工厂模式"的简单应用

6. **结构化日志的关键是 `[标签]`**：没有标签的日志就是一坨文字，有了标签就能 grep 过滤

---

## 六、还不熟、下次还要追问的问题

| 序号 | 问题 | 为什么重要 | 计划什么时候搞清楚 |
|------|------|-----------|-------------------|
| 1 | Result 类要不要加时间戳字段（timestamp）？ | 有些项目会加，用于排查问题 | 有空时对比其他项目的做法 |
| 2 | 全局异常处理器能处理拦截器里的异常吗？ | 拦截器在 Controller 之前执行，异常可能不会被 @RestControllerAdvice 捕获 | Day 5 遇到时验证 |
| 3 | @Valid 校验分组怎么做？（比如注册和更新用不同的校验规则） | 同一个 DTO 不同场景校验规则不同 | Day 5 做任务 CRUD 时 |
| 4 | Result 的 code 字段要不要用枚举代替硬编码数字？ | 硬编码 200/400/401/500 容易写错 | Day 5 考虑 |
| 5 | 日志级别（info/warn/error）怎么选？ | 打太多 info 日志会影响性能，打太少出问题查不到 | 后续实践积累 |

---

## 七、今日新增/修改文件清单

```
competitor-agent/
├── pom.xml                                  ← 加了 spring-boot-starter-validation
├── src/main/java/com/competitor/agent/
│   ├── common/
│   │   ├── Result.java                      ← 新增：统一响应类
│   │   ├── BusinessException.java           ← 新增：业务异常
│   │   └── GlobalExceptionHandler.java      ← 新增：全局异常处理器
│   ├── controller/
│   │   ├── AuthController.java              ← 修改：返回值改 Result + 加 @Valid
│   │   └── UserController.java              ← 修改：返回值改 Result
│   ├── dto/
│   │   ├── RegisterRequest.java             ← 修改：加 @NotBlank @Size 校验注解
│   │   └── LoginRequest.java                ← 修改：加 @NotBlank 校验注解
│   ├── interceptor/
│   │   └── JwtInterceptor.java              ← 修改：改用 Result + ObjectMapper
│   └── service/
│       └── SysUserService.java              ← 修改：RuntimeException→BusinessException + 加日志
```
