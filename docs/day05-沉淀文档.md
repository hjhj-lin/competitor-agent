# Day5 沉淀文档 — 任务 CRUD 接口

## 一、今天做了哪些功能

| 步骤 | 内容 | 接口 |
|------|------|------|
| 5-1 | 建表 + 状态枚举 + 实体类 | 无 |
| 5-2 | 创建任务 | POST /api/tasks |
| 5-3 | 查看任务列表（分页） | GET /api/tasks |
| 5-4 | 查看任务详情（权限校验） | GET /api/tasks/{id} |
| 5-5 | 删除任务（逻辑删除） | DELETE /api/tasks/{id} |

## 二、关键代码逻辑链路

### 创建任务的完整链路（最典型）

```
前端 POST /api/tasks + Authorization: Bearer xxx + {"companyName":"华为"}
    ↓
JwtInterceptor.preHandle()
    → 取 token → 解析出 userId=4, username="day5test"
    → request.setAttribute("currentUserId", 4)  ← 关键！Controller 从这里取
    → 放行
    ↓
TaskController.createTask()
    → @RequestAttribute("currentUserId") Long userId  ← 从拦截器塞的 attribute 取
    → @Valid @RequestBody CreateTaskRequest  ← @NotBlank 校验 companyName
    → 调用 Service
    ↓
AnalysisTaskService.createTask()
    → 组装 AnalysisTask 对象（status=PENDING, aiCallCount=0）
    → analysisTaskMapper.insert(task)
    ↓
MyBatis-Plus 自动生成 SQL：
    INSERT INTO analysis_task (user_id, company_name, status, ai_call_count, created_at, updated_at)
    VALUES (4, '华为', 'PENDING', 0, NOW(), NOW())
    ↓
MyMetaObjectHandler 自动填充 created_at / updated_at
    ↓
返回 Result.success(task) → Spring MVC 转 JSON → 前端拿到响应
```

### 查看列表的 SQL 链路

```
Service: LambdaQueryWrapper.eq(AnalysisTask::getUserId, userId).orderByDesc(AnalysisTask::getCreatedAt)
Mapper: selectPage(page, wrapper)
SQL: SELECT * FROM analysis_task WHERE user_id = 4 AND deleted = 0 ORDER BY created_at DESC LIMIT 0,10
```

### 查看详情的权限校验链路

```
Service.getTaskById(userId, taskId):
    1. selectById(taskId) → 查出 task
    2. task == null → 抛 "任务不存在"
    3. task.getUserId() != userId → 抛 "无权查看该任务"
    4. 返回 task
```

### 删除任务的逻辑删除链路

```
Service.deleteTask(userId, taskId):
    1. 同样的权限校验
    2. analysisTaskMapper.deleteById(taskId)
SQL: UPDATE analysis_task SET deleted = 1 WHERE id = 4
    ↑ @TableLogic 让 deleteById 变成 UPDATE 而不是 DELETE
    ↑ 后续所有查询自动加 WHERE deleted = 0
```

## 三、重要概念的人话解释

### 1. @RequestAttribute — 从 request 里取拦截器塞进去的值
- 人话：拦截器是门卫，把用户身份证查完后，在访客牌上写上"userId=4"贴你身上
- Controller 用 @RequestAttribute 就是看访客牌上的信息
- 跟 @RequestParam（取 URL 参数）、@PathVariable（取路径变量）、@RequestBody（取请求体）是同一类，只是取的来源不同

### 2. @TableLogic — 逻辑删除
- 人话：不是真把记录删了，而是在记录上盖个"已作废"的章（deleted=1）
- 好处：数据还在，可以恢复，可以审计
- MyBatis-Plus 自动做的事：
  - deleteById() → 变成 UPDATE SET deleted=1
  - selectById/selectList/selectPage → 自动加 WHERE deleted=0
- 你不需要手动写 WHERE deleted=0，框架帮你加的

### 3. MybatisPlusInterceptor 分页插件
- 人话：没有这个配置，selectPage 虽然不报错，但会返回全部数据，分页是假的
- 必须在配置类里注册 PaginationInnerInterceptor，分页才真正生效
- 原理：MyBatis-Plus 拦截 SQL，自动拼接 LIMIT 语句

### 4. LambdaQueryWrapper — 类型安全的查询条件
- 人话：用 Java 方法代替手写 SQL 的 WHERE 条件
- `wrapper.eq(AnalysisTask::getUserId, userId)` = `WHERE user_id = 4`
- `wrapper.orderByDesc(AnalysisTask::getCreatedAt)` = `ORDER BY created_at DESC`
- 好处：写错字段名编译期就报错，不用等运行时才发现 SQL 拼错了

### 5. @Valid + @NotBlank — 参数校验
- 人话：在 DTO 字段上贴标签，Spring 自动帮你检查，不通过就返回 400
- @NotBlank：不能为空字符串
- @Size(max=100)：最长 100 字符
- 校验失败 → MethodArgumentNotValidException → GlobalExceptionHandler 统一处理返回 400

## 四、联调中踩过的坑

### 坑1：MyBatis-Plus 分页不生效
- 现象：selectPage 返回全部数据，total 等于全部记录数
- 原因：没有配置 MybatisPlusInterceptor + PaginationInnerInterceptor
- 解决：新建 MybatisPlusConfig.java，注册分页插件

### 坑2：PowerShell 中文乱码
- 现象：Invoke-RestMethod 发送中文，数据库里存的是 ??
- 原因：PowerShell 默认编码不是 UTF-8，Invoke-RestMethod 的 -Body 字符串用系统编码
- 解决：用 `[System.Text.Encoding]::UTF8.GetBytes()` 把 body 转成 UTF-8 字节数组，ContentType 加 `;charset=utf-8`
- 注意：这只是 PowerShell 测试工具的问题，浏览器/前端发请求不会有这个问题

### 坑3：JwtInterceptor 只存了 username，没存 userId
- 现象：创建任务需要 userId，但拦截器只存了 currentUser(username)
- 原因：Day3 写拦截器时没考虑到后续需要 userId
- 解决：JwtUtil 新增 getUserIdFromToken()，拦截器多存一个 currentUserId

## 五、值得记住的经验

### 1. 安全校验的顺序：先判空，再判权限
```java
AnalysisTask task = analysisTaskMapper.selectById(taskId);
if (task == null) { throw ... }      // 先判空
if (!task.getUserId().equals(userId)) { throw ... }  // 再判权限
```
如果先判权限，task 为 null 时调 task.getUserId() 会空指针异常。

### 2. DTO 只暴露必要字段
CreateTaskRequest 只有 companyName，没有 userId/status/aiCallCount。
这些由后端自动填充，不让前端传，防止篡改。

### 3. 每个接口都要考虑"能不能操作别人的数据"
- 列表：WHERE user_id = 当前用户 → 天然隔离
- 详情/删除：先查出记录，再比 userId → 主动校验
- 这是"越权访问"漏洞的常见防护方式

## 六、还不熟、下次还要追问的问题

1. Page<AnalysisTask> 返回给前端时，deleted 字段也暴露了，前端不需要这个字段，怎么过滤掉？
   - 提示：可以用 VO（View Object）或者 @JsonIgnore

2. getTaskById 和 deleteTask 里的权限校验代码重复了（先查、判空、判权限），能不能抽成一个方法？
   - 提示：可以抽一个 checkOwnership(userId, taskId) 方法

3. 逻辑删除的记录，如果用户又创建了同名公司的任务，会不会有唯一索引冲突？
   - 当前没有唯一索引，不会冲突。但如果加了唯一索引，需要考虑 deleted 字段参与索引

4. 分页参数没有做上限校验，如果有人传 pageSize=100000 怎么办？
   - 可以在 Controller 或全局做参数范围校验

5. @TableLogic 的 deleted 字段，数据库默认值是 0，但 insert 时 MyBatis-Plus 不会自动设置 deleted=0，而是依赖数据库 DEFAULT 0。如果数据库没设默认值会怎样？
