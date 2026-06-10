package com.competitor.agent.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.competitor.agent.common.BusinessException;
import com.competitor.agent.common.Result;
import com.competitor.agent.service.ReportExportService;
import com.competitor.agent.service.ReportService;
import com.competitor.agent.vo.ReportVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "报告管理", description = "报告查询与PDF导出")
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReportService reportService;
    private final ReportExportService reportExportService;

    @Operation(summary = "搜索报告")
    @GetMapping("/search")
    public Result<IPage<ReportVO>> searchReports(
            @RequestAttribute("currentUserId") Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1) pageSize = 10;
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        IPage<ReportVO> page = reportService.searchReports(userId, keyword, pageNum, pageSize);
        return Result.success(page);
    }

    @Operation(summary = "获取报告详情")
    @GetMapping("/{id}")
    public Result<ReportVO> getReportById(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long id) {
        ReportVO vo = reportService.getReportById(userId, id);
        if (vo == null) {
            throw BusinessException.notFound("报告不存在");
        }
        return Result.success(vo);
    }

    @Operation(summary = "按报告ID导出PDF")
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportReport(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long id,
            @RequestParam(defaultValue = "pdf") String format) throws IOException {
        ReportVO report = reportService.getReportById(userId, id);
        if (report == null) {
            throw BusinessException.notFound("报告不存在");
        }
        return buildPdfResponse(report);
    }

    @Operation(summary = "按任务ID导出PDF")
    @GetMapping("/task/{taskId}/export")
    public ResponseEntity<byte[]> exportReportByTaskId(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long taskId) throws IOException {
        ReportVO report = reportService.getReportByTaskId(userId, taskId);
        if (report == null) {
            throw BusinessException.notFound("报告不存在");
        }
        return buildPdfResponse(report);
    }

    private ResponseEntity<byte[]> buildPdfResponse(ReportVO report) throws IOException {
        byte[] pdfBytes = reportExportService.exportPdf(report.getCompanyName(), report.getContent());
        String fileName = URLEncoder.encode(report.getCompanyName() + "_竞品分析报告.pdf", StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .body(pdfBytes);
    }
}
