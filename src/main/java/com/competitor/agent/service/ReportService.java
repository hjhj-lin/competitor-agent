package com.competitor.agent.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.competitor.agent.entity.Report;
import com.competitor.agent.mapper.ReportMapper;
import com.competitor.agent.vo.ReportVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportMapper reportMapper;

    public IPage<ReportVO> searchReports(Long userId, String keyword, int pageNum, int pageSize) {
        Page<Report> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Report::getUserId, userId);

        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(Report::getCompanyName, keyword)
                    .or()
                    .like(Report::getContent, keyword));
        }

        wrapper.orderByDesc(Report::getCreatedAt);

        Page<Report> reportPage = reportMapper.selectPage(page, wrapper);
        return reportPage.convert(this::toVO);
    }

    public ReportVO getReportById(Long userId, Long reportId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null || !report.getUserId().equals(userId)) {
            return null;
        }
        return toVO(report);
    }

    public ReportVO getReportByTaskId(Long userId, Long taskId) {
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Report::getUserId, userId)
               .eq(Report::getTaskId, taskId)
               .orderByDesc(Report::getCreatedAt)
               .last("LIMIT 1");
        Report report = reportMapper.selectOne(wrapper);
        return report != null ? toVO(report) : null;
    }

    private ReportVO toVO(Report report) {
        ReportVO vo = new ReportVO();
        vo.setId(report.getId());
        vo.setTaskId(report.getTaskId());
        vo.setCompanyName(report.getCompanyName());
        vo.setContent(report.getContent());
        vo.setCreatedAt(report.getCreatedAt());
        return vo;
    }
}
