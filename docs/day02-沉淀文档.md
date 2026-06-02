# Day 2 沉淀文档 —— MySQL + MyBatis-Plus + 配置管理

日期：2026-05-26

---

## 一、今天做了哪些功能

| 序号 | 功能 | 验证方式 | 结果 |
|------|------|----------|------|
| 1 | 创建 `competitor_agent` 数据库 + `sys_user` 表 | `DESC sys_user` 查看表结构 | ✅ 通过 |
| 2 | 插入测试数据 testuser | `SELECT * FROM sys_user` | ✅ 通过 |
| 3 | pom.xml 加 MyBatis-Plus + MySQL 驱动 + Caffeine + Lombok 依赖 | 项目编译无报错 | ✅ 通过 |
| 4 | 多环境配置：application.yml + application-dev.yml | 启动时加载 dev 配置 | ✅ 通过 |
| 5 | MySQL 密码从环境变量读取 `${MYSQL_PASSWORD:123456}` | 没设环境变量时用了默认值 123456 | ✅ 通过 |
| 6 | 创建 SysUser 实体类（含 @TableLogic、@TableField 自动填充） | 编译无报错 | ✅ 通过 |
| 7 | 创建 SysUserMapper 继承 BaseMapper | 能调 selectList/insert | ✅ 通过 |
| 8 | 创建 SysUserService | listUsers/getUserById/createUser | ✅ 通过 |
| 9 | 创建 UserController | GET /api/users 返回数据库数据 | ✅ 通过 |
| 10 | MyMetaObjectHandler 自动填充 createdAt/updatedAt | 插入数据时自动填时间 | ✅ 通过 |
| 11 | 启动类加 @MapperScan | Mapper 被正确扫描到 | ✅ 通过 |
| 12 | .gitignore 排除 .env、application-prod.yml | 敏感信息不提交 | ✅ 通过 |

---

## 二、关键代码逻辑链路

### 查询请求完整旅程

```
前端发 GET /api/users
    ↓
① DispatcherServlet 查路由 → 找到 UserController.listUsers()
    ↓
② UserController 调 SysUserService.listUsers()
    ↓
③ SysUserService 调 SysUserMapper.selectList(null)
   → null 表示没有查询条件，查全部
    ↓
④ MyBatis-Plus 自动生成 SQL：
   SELECT id, username, password, email, daily_task_limit, deleted, created_at, updated_at
   FROM sys_user
   WHERE deleted = 0
    ↓
⑤ MySQL 执行 SQL，返回结果集
    ↓
⑥ MyBatis-Plus 把结果集映射成 List<SysUser>
   → daily_task_limit → dailyTaskLimit（下划线转驼峰）
   → deleted=0 的记录才查出来（逻辑删除自动过滤）
    ↓
⑦ Spring 把 List<SysUser> 序列化成 JSON 返回
```

### 涉及的文件和职责

| 文件 | 职责 | 一句话理解 |
|------|------|-----------|
| `SysUser.java` | 实体类，映射数据库表 | 数据库表的 Java 版说明书 |
| `SysUserMapper.java` | 数据访问层，继承 BaseMapper | 仓库管理员，管和数据库打交道 |
| `SysUserService.java` | 业务逻辑层 | 后厨，管业务规则 |
| `UserController.java` | 接口层，接收 HTTP 请求 | 前台接待员，管接请求和返响应 |
| `MyMetaObjectHandler.java` | 自动填充处理器 | 时间字段自动填，不用每次手动 set |
| `application.yml` | 公共配置 + MyBatis-Plus 配置 | 设置面板 |
| `application-dev.yml` | 开发环境配置 | MySQL 连接信息 |
| `CompetitorAgentApplication.java` | 启动类 + @MapperScan | 总开关 + 告诉 Spring 去哪找 Mapper |

---

## 三、重要概念的人话解释

### MyBatis-Plus
- 人话：MyBatis 的升级版，不用写 SQL 就能完成 CRUD。继承 BaseMapper 就有 selectList/insert/update/delete 方法
- 本质：在 MyBatis 基础上加了代码生成、条件构造器、分页、逻辑删除等功能

### BaseMapper<SysUser>
- 人话：MyBatis-Plus 提供的通用接口，继承它就自动拥有所有基础 CRUD 方法
- 常用方法：selectList（查全部）、selectById（按 ID 查）、insert（插入）、updateById（按 ID 更新）、deleteById（按 ID 删除）

### @TableName("sys_user")
- 人话：告诉 MyBatis-Plus 这个 Java 类对应数据库里的哪张表
- 不加的话：默认找表名 `sys_user`（类名转小写+下划线），但显式写更清晰

### @TableId(type = IdType.AUTO)
- 人话：标记主键，IdType.AUTO 表示数据库自增
- 不加的话：默认用雪花算法生成 ID（Long 类型的随机大数）

### @TableLogic
- 人话：逻辑删除。标记这个字段后，所有查询自动加 `WHERE deleted = 0`，删除时变成 `UPDATE ... SET deleted = 1`
- 和物理删除的区别：物理删除是 `DELETE FROM` 数据真没了；逻辑删除是标记 deleted=1，数据还在表里，只是查不到

### @TableField(fill = FieldFill.INSERT)
- 人话：插入时自动填充这个字段。配合 MyMetaObjectHandler 使用
- 效果：insert 时不用手动 set createdAt，MyBatis-Plus 自动填当前时间

### LambdaQueryWrapper
- 人话：用 Lambda 表达式构建 SQL 查询条件
- 好处：用 `SysUser::getUsername` 而不是字符串 `"username"`，写错字段名编译时就能发现
- 示例：`wrapper.eq(SysUser::getUsername, "test")` → `WHERE username = 'test'`

### @RequiredArgsConstructor
- 人话：Lombok 注解，自动生成包含所有 final 字段的构造函数
- 作用：替代 `@Autowired` 字段注入，推荐用构造器注入
- 好处：final 保证注入后不会被改（线程安全），测试时可以手动传 mock 对象

### @MapperScan("com.competitor.agent.mapper")
- 人话：告诉 Spring 去哪找 Mapper 接口。扫描这个包下的所有 @Mapper
- 不加的话：每个 Mapper 都要加 @Mapper 注解，或者 Spring 找不到 Mapper 报错

### 多环境配置
- 人话：不同环境（开发/测试/生产）用不同的配置。application.yml 放公共配置，application-dev.yml 放开发环境配置
- Spring Boot 自动根据 `spring.profiles.active` 加载对应环境的配置文件

### ${MYSQL_PASSWORD:123456}
- 人话：读环境变量 MYSQL_PASSWORD，没有就用默认值 123456
- 冒号前面是变量名，冒号后面是默认值
- 好处：开发时不设置就用默认值，生产时设置环境变量，密码不写死在代码里

---

## 四、联调中踩过的坑

### 坑1：PowerShell 的 curl 不是 Linux 的 curl
- 现象：`curl -s http://localhost:8080/api/users` 报错"找不到驱动器"
- 原因：PowerShell 里 curl 是 Invoke-WebRequest 的别名，不支持 `-s` 参数
- 解决：用 `Invoke-RestMethod -Uri "http://localhost:8080/api/users" -Method Get`

### 坑2：表名 user 是 MySQL 保留字
- 现象：建表报错或需要加反引号
- 解决：改表名为 `sys_user`，避免和保留字冲突

---

## 五、值得记住的经验

1. **Controller 不直接调 Mapper**：Controller→Service→Mapper→数据库，每一层只和相邻的层打交道，这叫分层架构。如果 Controller 直接调 Mapper，以后业务逻辑变复杂时就没地方放了。

2. **永远用构造器注入，不用 @Autowired 字段注入**：`@RequiredArgsConstructor` + `final` 比 `@Autowired` 更好，因为 final 保证不可变（线程安全），测试友好。

3. **逻辑删除比物理删除更安全**：误删数据可以恢复，审计有迹可查。MyBatis-Plus 的 @TableLogic 自动处理，不用每次手动写 WHERE deleted=0。

4. **多环境配置从第一天就要做**：不要等到要部署时才想起来。开发时用 dev，生产时用 prod，密码等敏感信息放环境变量，不写死在代码里。

5. **下划线转驼峰必须配置**：数据库字段用下划线（daily_task_limit），Java 字段用驼峰（dailyTaskLimit），MyBatis-Plus 默认不转换，必须配置 `map-underscore-to-camel-case: true`。

6. **先跑通 CRUD，再上复杂功能**：Day 2 只做一件事——让项目能连数据库、能查数据、能插数据。不急着做用户注册登录，那是 Day 3 的事。

---

## 六、还不熟、下次还要追问的问题

| 序号 | 问题 | 为什么重要 | 计划什么时候搞清楚 |
|------|------|-----------|-------------------|
| 1 | LambdaQueryWrapper 的高级用法（like/in/between/or）怎么写？ | 后面查任务列表会用到复杂条件 | Day 5 做任务 CRUD 时 |
| 2 | @TableField(fill=...) 的 strictInsertFill 和 insertFill 有什么区别？ | 理解自动填充的细节 | 有空时查官方文档 |
| 3 | MyBatis-Plus 的分页怎么配？ | 后面用户列表、任务列表肯定需要分页 | Day 5 做任务列表时 |
| 4 | BaseMapper 的 selectList(null) 返回的是懒加载吗？ | 大数据量时会不会一次全查出来 | Day 5 做分页时 |
| 5 | 多数据源怎么配？（如果以后需要连多个数据库） | 知识储备 | 项目后期 |

---

## 七、今日新增文件清单

```
competitor-agent/
├── pom.xml                          ← 加了 MyBatis-Plus/MySQL/Caffeine/Lombok 依赖
├── .gitignore                       ← 排除 .env/application-prod.yml
├── src/main/java/com/competitor/agent/
│   ├── CompetitorAgentApplication.java   ← 加了 @MapperScan
│   ├── entity/
│   │   └── SysUser.java               ← 新增：用户实体类
│   ├── mapper/
│   │   └── SysUserMapper.java         ← 新增：用户 Mapper
│   ├── service/
│   │   └── SysUserService.java        ← 新增：用户 Service
│   ├── controller/
│   │   ├── HelloController.java       ← 删除（合并到 UserController）
│   │   └── UserController.java        ← 新增：用户 Controller（含 hello 接口）
│   └── config/
│       └── MyMetaObjectHandler.java   ← 新增：自动填充处理器
└── src/main/resources/
    ├── application.yml                ← 加了 MyBatis-Plus 配置 + profiles.active
    └── application-dev.yml            ← 新增：开发环境配置（MySQL 连接信息）
```
