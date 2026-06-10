package com.competitor.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ReportExportServiceTest {

    private ReportExportService reportExportService;

    @BeforeEach
    void setUp() {
        reportExportService = new ReportExportService();
    }

    @Test
    void exportPdf_simpleMarkdown_generatesPdf() throws IOException {
        String markdown = "# 测试标题\n\n这是测试内容\n\n## 二级标题\n\n- 列表项1\n- 列表项2";
        byte[] pdf = reportExportService.exportPdf("华为", markdown);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0, "PDF字节数组不应为空");
        // 验证PDF文件头
        String header = new String(pdf, 0, 5);
        assertEquals("%PDF-", header, "应为PDF文件头");
    }

    @Test
    void exportPdf_tableMarkdown_generatesPdf() throws IOException {
        String markdown = "# 报告\n\n| 指标 | 公司A | 公司B |\n|------|-------|-------|\n| 营收 | 100亿 | 80亿 |";
        byte[] pdf = reportExportService.exportPdf("腾讯", markdown);

        assertNotNull(pdf);
        assertTrue(pdf.length > 100, "含表格的PDF应有一定大小");
    }

    @Test
    void exportPdf_emptyContent_generatesPdf() throws IOException {
        byte[] pdf = reportExportService.exportPdf("空公司", "");

        assertNotNull(pdf);
        assertTrue(pdf.length > 0, "空内容也应生成PDF（含封面）");
    }
}
