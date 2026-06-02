package com.competitor.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.competitor.agent.common.BusinessException;
import com.competitor.agent.common.Result;
import com.competitor.agent.service.ReportService;
import com.competitor.agent.vo.ReportVO;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReportService reportService;

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
}
