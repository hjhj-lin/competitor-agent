# Day 1 沉淀文档 —— 项目初始化 + 第一个 API

日期：2026-05-25

---

## 一、今天做了哪些功能

| 序号 | 功能                                  | 验证方式                                     | 结果                                                                |
| ---- | ------------------------------------- | -------------------------------------------- | ------------------------------------------------------------------- |
| 1    | 确认开发环境（JDK 21 + Maven 3.9.11） | 命令行 `java -version` / `mvn -version`      | ✅ 通过                                                             |
| 2    | 创建 Spring Boot 3.2.5 项目骨架       | 项目目录结构完整                             | ✅ 通过                                                             |
| 3    | 编写 HelloController                  | 代码无编译错误                               | ✅ 通过                                                             |
| 4    | 启动项目并验证接口                    | 浏览器访问 `http://localhost:8080/api/hello` | ✅ 返回 `{"message":"hello, competitor-agent!","status":"running"}` |

---

## 二、关键代码逻辑链路

### 请求完整旅程

```
浏览器输入 http://localhost:8080/api/hello
    ↓
① DNS 解析：localhost → 127.0.0.1
    ↓
② 浏览器发出 HTTP 请求：GET /api/hello，目标端口 8080
    ↓
③ Spring Boot 内置 Tomcat 服务器监听到请求
    ↓
④ DispatcherServlet（总调度员）接手请求
    ↓
⑤ DispatcherServlet 查路由表：谁处理 /api/hello？
   → 找到 HelloController.hello() 方法
    ↓
⑥ 执行 hello()，返回 Map.of("message","hello...","status","running")
    ↓
⑦ Spring 调用 Jackson 库，把 Map 自动序列化为 JSON 字符串
    ↓
⑧ Tomcat 包装成 HTTP 响应：状态码 200，Content-Type: application/json
    ↓
⑨ 浏览器收到响应，展示 JSON
```

### 涉及的文件和职责

| 文件                              | 职责                       | 一句话理解                           |
| --------------------------------- | -------------------------- | ------------------------------------ |
| `pom.xml`                         | 管理依赖和构建配置         | 购物清单，告诉 Maven 需要哪些 jar 包 |
| `CompetitorAgentApplication.java` | 程序入口，启动 Spring 容器 | 总开关，运行 main 方法整个项目就活了 |
| `HelloController.java`            | 接收 HTTP 请求，返回响应   | 前台接待员，用户敲门的地方           |
| `application.yml`                 | 项目配置                   | 设置面板，端口号、应用名都在这       |

---

## 三、重要概念的人话解释

### @RestController

- 人话：告诉 Spring "这个类是专门接 HTTP 请求的，方法的返回值直接当响应体返回，不要跳转页面"
- 本质：`@Controller` + `@ResponseBody` 的合体
- 如果只用 `@Controller`，Spring 会以为你要返回一个网页（视图名），不是 JSON

### @RequestMapping("/api")

- 人话：这个类里所有接口的 URL 都以 `/api` 开头
- 作用：统一路径前缀，避免和其他 Controller 的路径冲突

### @GetMapping("/hello")

- 人话：处理 GET 请求，完整路径是 `/api/hello`（拼上类的 @RequestMapping）
- 兄弟注解：`@PostMapping`（新增）、`@PutMapping`（修改）、`@DeleteMapping`（删除）

### DispatcherServlet

- 人话：Spring 的总调度员/前台经理
- 工作方式：所有请求先到它，它根据 URL 查路由表，找到对应的 Controller 方法，把请求转过去
- 不用我们写，Spring Boot 自动配置好的

### Map.of()

- 人话：Java 9+ 提供的快速创建不可变 Map 的方法
- 不可变 = 创建后不能修改（不能 put/remove）
- 适合当接口返回值，因为不会被意外篡改

### SpringApplication.run()

- 人话：一行代码启动整个 Spring Boot 应用
- 它做了三件事：① 创建 Spring 容器 ② 启动内置 Tomcat ③ 执行自动配置

### application.yml

- 人话：Spring Boot 的设置面板
- 比 `.properties` 格式更清晰，支持层级缩进
- 修改后重启项目生效

---

## 四、联调中踩过的坑

### 坑1：Windows PowerShell 不支持 `&&` 连接命令

- 现象：`cd e:\11\competitor-agent && mvn spring-boot:run` 报错"标记'&&'不是有效语句分隔符"
- 原因：PowerShell 用分号 `;` 连接命令，不支持 `&&`
- 解决：用 `cwd` 参数指定工作目录，而不是用 `cd` 切换

### 坑2：首次启动 Maven 下载依赖很慢

- 现象：第一次 `mvn spring-boot:run` 要等很久
- 原因：Maven 从中央仓库下载所有依赖 jar 包
- 经验：只下载一次，后续启动很快（本地仓库已缓存）
- 优化：已配置阿里云 Maven 镜像，下载速度会快很多

---

## 五、值得记住的经验

1. **先跑通再迭代**：Day 1 的目标不是写出完美代码，而是确认环境没问题、项目能启动、接口能访问。这叫"冒烟测试"。

2. **验证方式要具体**：不说"应该能跑"，而是"浏览器访问 `http://localhost:8080/api/hello` 应该返回 `{"message":"hello, competitor-agent!"}`"。越具体越好排查。

3. **Controller 是入口，不是干活的**：Controller 只负责接请求和返响应，真正的业务逻辑应该放在 Service 层（Day 2+ 会加）。

4. **约定大于配置**：Spring Boot 的核心思想。只要按约定放好文件（Controller 在扫描包下、配置文件叫 application.yml），不用写 XML 就能跑。

5. **JSON 是前后端通信的通用语言**：后端返回 Java 对象（Map/实体类），Spring 自动转成 JSON，前端直接用。这个转换靠 Jackson 库完成。

---

## 六、还不熟、下次还要追问的问题

| 序号 | 问题                                                                    | 为什么重要                           | 计划什么时候搞清楚 |
| ---- | ----------------------------------------------------------------------- | ------------------------------------ | ------------------ |
| 1    | `@ComponentScan` 具体扫描了哪些包？如果 Controller 放到别的包下会怎样？ | 理解 Spring 的组件扫描机制，避免 404 | Day 2              |
| 2    | DispatcherServlet 是怎么知道 `/api/hello` 对应哪个方法的？              | 理解 Spring 的路由注册机制           | Day 2              |
| 3    | Map 返回值是怎么变成 JSON 的？Jackson 的自动配置原理？                  | 理解 Spring Boot 的自动配置思想      | Day 3              |
| 4    | `spring-boot-starter-web` 到底引入了哪些依赖？                          | 理解 starter 的依赖传递              | Day 2              |
| 5    | application.yml 和 application.properties 有什么区别？能不能混用？      | 配置文件的选择                       | Day 2              |

---

## 附录：今日项目文件清单

```
competitor-agent/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/competitor/agent/
│   │   │   ├── CompetitorAgentApplication.java
│   │   │   └── controller/
│   │   │       └── HelloController.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/                          （暂无测试代码）
```
