# Day 3 沉淀文档 —— 用户注册 + 登录 + JWT 鉴权

日期：2026-05-26

---

## 一、今天做了哪些功能

| 序号 | 功能 | 验证方式 | 结果 |
|------|------|----------|------|
| 1 | 加 JWT 依赖（jjwt-api/jjwt-impl/jjwt-jackson）+ spring-security-crypto | 项目编译通过 | ✅ |
| 2 | JWT 配置（密钥 + 过期时间）到 application-dev.yml | 启动无报错 | ✅ |
| 3 | JwtUtil 工具类（生成 token / 解析 token / 校验 token） | 单元逻辑在登录接口中验证 | ✅ |
| 4 | 注册接口（BCrypt 加密存储密码） | 注册后数据库密码是密文 | ✅ |
| 5 | 登录接口（BCrypt 比对 + 返回 JWT token） | 登录返回 eyJhbGci... 开头的 token | ✅ |
| 6 | JwtInterceptor 拦截器（校验 token） | 不带 token 访问 /api/users → 401 | ✅ |
| 7 | WebMvcConfig 放行配置（注册/登录/hello 不需要 token） | 这三个接口不带 token 也能访问 | ✅ |
| 8 | 前端测试页面（index.html） | 浏览器操作注册→登录→带token访问 | ✅ |

---

## 二、关键代码逻辑链路

### 注册链路

```
POST /api/auth/register {"username":"zhangsan","password":"abc123","email":"..."}
    ↓
WebMvcConfig: /api/auth/register 在排除名单 → 不调用拦截器
    ↓
AuthController.register() → @RequestBody 转 RegisterRequest
    ↓
SysUserService.register():
    1. getUserByUsername() 查用户名是否已存在 → 存在则抛异常
    2. passwordEncoder.encode("abc123") → "$2a$10$Ap2Bt..."（BCrypt 加密）
    3. sysUserMapper.insert(user) → 入库
    ↓
返回 {"id":2, "username":"zhangsan"}
```

### 登录链路

```
POST /api/auth/login {"username":"zhangsan","password":"abc123"}
    ↓
WebMvcConfig: /api/auth/login 在排除名单 → 不调用拦截器
    ↓
AuthController.login() → @RequestBody 转 LoginRequest
    ↓
SysUserService.login():
    1. getUserByUsername("zhangsan") → 从数据库查用户
    2. passwordEncoder.matches("abc123", "$2a$10$Ap2Bt...") → BCrypt 比对
    3. jwtUtil.generateToken(userId, username) → 生成 JWT
    ↓
返回 {"token":"eyJhbGci...", "username":"zhangsan"}
```

### 带 token 访问受保护接口链路

```
GET /api/users  Header: Authorization: Bearer eyJhbGci...
    ↓
WebMvcConfig: /api/users 不在排除名单 → 调用 JwtInterceptor
    ↓
JwtInterceptor.preHandle():
    1. request.getHeader("Authorization") → 取 "Bearer eyJhbGci..."
    2. substring(7) → 去掉 "Bearer "，剩下纯 token
    3. jwtUtil.isTokenValid(token) → 解析 + 验证签名 + 检查过期
    4. 验证通过 → request.setAttribute("currentUser", username) → return true
    ↓ (验证通过)
UserController.listUsers() → 查数据库 → 返回用户列表
    ↓ (验证失败)
返回 401 {"code":401,"message":"token无效或已过期"}，请求到不了 Controller
```

### 涉及的关键文件

| 文件 | 职责 | 一句话理解 |
|------|------|-----------|
| `JwtInterceptor.java` | 拦截请求，校验 token | 安检员，查你有没有通行证 |
| `WebMvcConfig.java` | 配置哪些路径拦截、哪些放行 | 安检规则，哪些门需要安检 |
| `SysUserService.java` | 注册/登录业务逻辑 | 后厨，管校验、加密、生成 token |
| `JwtUtil.java` | JWT 生成和校验 | 印章，盖章和验章 |
| `AuthController.java` | 注册/登录接口 | 前台，只管接请求和返响应 |

---

## 三、重要概念的人话解释

### JWT（JSON Web Token）
- 人话：一张盖了章的通行证。上面写了你是谁（username）、你的 ID（userId）、什么时候发的（iat）、什么时候过期（exp）。盖了章（签名）之后谁也改不了，改了章就对不上
- 三部分：Header（算法）. Payload（数据）. Signature（签名）
- 和 Session 的区别：Session 存在服务器内存里，JWT 存在客户端。服务器不用存 JWT，验签名就行

### BCrypt
- 人话：一种单向加密算法。只能加密，不能解密。每次加密同样的密码结果都不一样（因为加了随机盐）
- 验证密码：不是解密后比较，而是用同样的盐加密明文，再和密文比较
- 为什么不用 MD5：MD5 是确定性的（同样的输入永远是同样的输出），可以用彩虹表反查。BCrypt 每次加密结果不同，彩虹表无效

### HandlerInterceptor
- 人话：Spring MVC 的拦截器机制。请求到 Controller 之前先经过拦截器，拦截器决定放行还是拦截
- 三个方法：preHandle（请求前）、postHandle（请求后）、afterCompletion（视图渲染后）
- return true = 放行，return false = 拦截（请求到不了 Controller）

### WebMvcConfigurer
- 人话：Spring MVC 的配置接口。实现它就能自定义拦截器、跨域、静态资源映射等
- addInterceptors：注册拦截器 + 配置拦截/排除路径
- addPathPatterns：哪些路径需要拦截
- excludePathPatterns：哪些路径不需要拦截（白名单）

### @RequestBody
- 人话：告诉 Spring 把 HTTP 请求体的 JSON 自动转成 Java 对象
- 比如 `{"username":"zhangsan","password":"abc123"}` → RegisterRequest 对象
- 靠 Jackson 库完成 JSON → Java 对象的转换

### DTO（Data Transfer Object）
- 人话：前后端之间传递数据的"快递包裹"。RegisterRequest 和 LoginRequest 就是 DTO
- 为什么不直接用 SysUser：SysUser 有 password、deleted 等字段，不应该暴露给前端。DTO 只放前端需要传的字段

---

## 四、联调中踩过的坑

### 坑1：UserController 还调了已删除的 createUser 方法
- 现象：编译报错"找不到符号 createUser"
- 原因：SysUserService 里把 createUser 删了（改成了 register），但 UserController 还在调
- 教训：改 Service 方法时，要检查所有调用方

### 坑2：PowerShell 的 curl 不是 Linux 的 curl
- 现象：`curl -s` 报错
- 解决：用 `Invoke-RestMethod`

### 坑3：前端页面跨域问题（目前没遇到，但迟早会）
- 目前没遇到是因为页面放在 Spring Boot 的 static 目录下，同源
- 以后前后端分离部署时一定会遇到，需要在 WebMvcConfig 里加跨域配置

---

## 五、值得记住的经验

1. **密码永远不能明文存储**：数据库泄露时，BCrypt 密文无法反推明文。MD5 不行（彩虹表），SHA256 不行（没有盐），BCrypt 是当前最佳实践

2. **登录失败不要区分"用户名不存在"和"密码错误"**：统一提示"用户名或密码错误"，防止攻击者枚举用户名

3. **拦截器的排除名单要和业务接口同步**：新增不需要鉴权的接口时，必须记得加到 excludePathPatterns，否则会被拦截

4. **Controller 不写业务逻辑**：注册时的"校验用户名是否重复"、"密码加密"都在 Service 里，Controller 只管接请求和返响应

5. **JWT 的密钥不能写死在代码里**：用 `${JWT_SECRET:默认值}` 从环境变量读，生产环境设真实密钥

6. **先跑通一个最小闭环再往下走**：Day 3 拆成了 6 步（依赖→注册→登录→拦截器→放行配置→验证），每步都验证通过才做下一步

---

## 六、还不熟、下次还要追问的问题

| 序号 | 问题 | 为什么重要 | 计划什么时候搞清楚 |
|------|------|-----------|-------------------|
| 1 | JWT token 过期了怎么办？前端怎么无感刷新？ | 用户体验，不能让用户每 24 小时重新登录 | Day 4 做统一响应时考虑 |
| 2 | 多设备登录怎么处理？同一个用户在两台电脑登录，token 怎么管理？ | 实际项目常见需求 | 项目后期 |
| 3 | 如果需要"踢人下线"（让已发的 token 失效），JWT 做不到怎么办？ | JWT 的已知局限：一旦发出就无法撤销 | 项目后期（可能需要 Redis 黑名单） |
| 4 | BCrypt 的强度参数（10/11/12）怎么选？ | 影响加密速度和安全性 | 有空查文档 |
| 5 | 前后端分离时跨域怎么配？ | 以后一定会遇到 | Day 13 做前端时 |
| 6 | @RequestBody 和 @RequestParam 的区别？ | 接口设计时需要知道用哪个 | Day 4 |
| 7 | 异常现在用 throw new RuntimeException，这样好吗？ | 不够友好，没有统一格式 | Day 4 做全局异常处理 |

---

## 七、今日新增/修改文件清单

```
competitor-agent/
├── pom.xml                              ← 加了 jjwt + spring-security-crypto 依赖
├── src/main/java/com/competitor/agent/
│   ├── controller/
│   │   ├── AuthController.java          ← 新增：注册/登录接口
│   │   └── UserController.java          ← 修改：删了 createUser，只保留查询
│   ├── dto/
│   │   ├── RegisterRequest.java         ← 新增：注册请求 DTO
│   │   └── LoginRequest.java            ← 新增：登录请求 DTO
│   ├── interceptor/
│   │   └── JwtInterceptor.java          ← 新增：JWT 拦截器
│   ├── config/
│   │   └── WebMvcConfig.java            ← 新增：拦截器注册 + 放行配置
│   ├── service/
│   │   └── SysUserService.java          ← 修改：加了 register/login 方法
│   └── util/
│       └── JwtUtil.java                 ← 新增：JWT 生成/解析/校验工具类
└── src/main/resources/
    ├── application-dev.yml              ← 修改：加了 jwt.secret + jwt.expiration
    └── static/
        └── index.html                   ← 新增：前端测试页面
```
