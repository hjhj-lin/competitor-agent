package com.competitor.agent.service;

import com.competitor.agent.entity.Report;
import com.competitor.agent.mapper.ReportMapper;
import com.competitor.agent.vo.ReportVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock private ReportMapper reportMapper;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(reportMapper);
    }

    private Report buildReport(Long id, Long userId, Long taskId, String companyName) {
        Report r = new Report();
        r.setId(id);
        r.setUserId(userId);
        r.setTaskId(taskId);
        r.setCompanyName(companyName);
        r.setContent("# " + companyName + " 报告内容");
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    @Test
    void getReportById_success() {
        Report report = buildReport(1L, 100L, 50L, "华为");
        when(reportMapper.selectById(1L)).thenReturn(report);

        ReportVO vo = reportService.getReportById(100L, 1L);

        assertNotNull(vo);
        assertEquals("华为", vo.getCompanyName());
        assertEquals(1L, vo.getId());
    }

    @Test
    void getReportById_wrongUser_returnsNull() {
        Report report = buildReport(1L, 100L, 50L, "华为");
        when(reportMapper.selectById(1L)).thenReturn(report);

        ReportVO vo = reportService.getReportById(999L, 1L);

        assertNull(vo);
    }

    @Test
    void getReportById_notFound_returnsNull() {
        when(reportMapper.selectById(999L)).thenReturn(null);

        ReportVO vo = reportService.getReportById(100L, 999L);

        assertNull(vo);
    }

    @Test
    void getReportByTaskId_success() {
        Report report = buildReport(1L, 100L, 50L, "华为");
        when(reportMapper.selectOne(any())).thenReturn(report);

        ReportVO vo = reportService.getReportByTaskId(100L, 50L);

        assertNotNull(vo);
        assertEquals("华为", vo.getCompanyName());
    }

    @Test
    void getReportByTaskId_notFound_returnsNull() {
        when(reportMapper.selectOne(any())).thenReturn(null);

        ReportVO vo = reportService.getReportByTaskId(100L, 999L);

        assertNull(vo);
    }
}
