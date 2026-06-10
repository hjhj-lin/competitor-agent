# Day 24 沉淀文档 — PDF导出 + SpringDoc API文档

## 一、今日目标

模块5工程完善的第一天：报告导出PDF + SpringDoc API文档

## 二、完成内容

### 2.1 PDF导出

**链路**：Markdown → HTML → PDF

```
用户点击"导出PDF"
  → exportPdf() fetch请求 (带JWT)
    → ReportController.exportReportByTaskId()
      → ReportService.getReportByTaskId() 查DB
        → ReportExportService.exportPdf()
          → flexmark: Markdown → HTML
          → openhtmltopdf: HTML → PDF (含封面+样式)
        → 返回 byte[] + Content-Disposition
      → 前端 Blob → 创建下载链接
```

**新增文件**：
- `ReportExportService.java` — 核心转换服务
  - flexmark解析Markdown为HTML
  - openhtmltopdf渲染HTML为PDF
  - 内置封面页（公司名+标题）+ 中文宋体样式

**修改文件**：
- `ReportService.java` — 新增 `getReportByTaskId()` 方法
- `ReportController.java` — 新增2个导出端点：
  - `GET /api/reports/{id}/export` — 按报告ID导出
  - `GET /api/reports/task/{taskId}/export` — 按任务ID导出
- `index.html` — 报告区域添加"导出PDF"按钮 + fetch Blob下载逻辑

**验证**：TaskId=57 → 22KB PDF，文件头`%PDF-`正确

### 2.2 SpringDoc API文档

**新增文件**：
- `SpringDocConfig.java` — OpenAPI配置
  - 标题/描述/版本
  - JWT Bearer安全方案（Swagger UI可直接填Token测试）

**修改文件**：5个Controller加`@Tag` + `@Operation`注解

| Controller | @Tag | 端点数 |
|-----------|------|-------|
| AuthController | 认证管理 | 2 |
| UserController | 用户管理 | 3 |
| TaskController | 任务管理 | 5 |
| ReportController | 报告管理 | 4 |
| PromptController | Prompt管理 | 3 |

**访问**：`/swagger-ui.html` 或 `/v3/api-docs`

## 三、新增依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| flexmark-all | 0.64.8 | Markdown → HTML |
| openhtmltopdf-pdfbox | 1.0.10 | HTML → PDF |
| springdoc-openapi-starter-webmvc-ui | 2.8.6 | API文档 |

## 四、关键设计决策

1. **两个导出端点**：报告详情页用reportId，任务详情页用taskId，避免前端需要先查reportId
2. **fetch + Blob下载**：`<a>`标签无法发送Authorization头，改用fetch获取PDF后创建Blob URL下载
3. **PDF封面**：在HTML模板中内置`.cover`区域，显示"XX公司 竞品分析报告"
4. **中文支持**：CSS指定`font-family: SimSun, serif`，确保PDF中文正常渲染

## 五、问题清单

| 问题 | 状态 | 说明 |
|------|------|------|
| PDF中文可能乱码 | 待验证 | openhtmltopdf默认不含中文字体，依赖系统SimSun字体 |
| PDF表格样式简单 | 低优先级 | 当前仅基础边框，后续可优化 |
| 导出按钮仅COMPLETED状态显示 | 正常 | RUNNING/FAILED无报告可导出 |

## 六、Day 17-24 整体进度

| 天 | 模块 | 内容 | 状态 |
|---|------|------|------|
| 17 | 框架重构 | BaseReActAgent + ReadReportTool | ✅ |
| 18-19 | 流式输出 | Token级流式 + 前端Markdown渲染 | ✅ |
| 20 | 容错 | Resilience4j熔断(tavily+aiCall) | ✅ |
| 21 | 容错 | @RateLimit限流 + 指数退避重试 | ✅ |
| 22 | 配置 | Prompt外部化(DB+缓存+热更新) | ✅ |
| 23 | 配置 | 多模型per-request + Kimi框架 | ✅ |
| 24 | 工程 | PDF导出 + SpringDoc | ✅ |
| 25 | 工程 | 待定 | 🔲 |
