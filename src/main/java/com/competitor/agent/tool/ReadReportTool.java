package com.competitor.agent.tool;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.competitor.agent.entity.Report;
import com.competitor.agent.mapper.ReportMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 报告工具 - 供Spring AI Tool Calling使用
 * 让AI能读取同一用户的历史竞品分析报告
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadReportTool {

    private final ReportMapper reportMapper;

    /** ThreadLocal存储当前用户ID，由PipelineExecutionService设置 */
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static void clearUserId() {
        CURRENT_USER_ID.remove();
    }

    @Tool(description = "读取当前用户的历史竞品分析报告，用于参考或对比。参数companyName为要查询的公司名称，会返回该公司最近一份报告的内容摘要。")
    public String readHistoryReport(String companyName) {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            return "（无法获取用户信息，无法读取历史报告）";
        }

        try {
            LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Report::getUserId, userId)
                   .like(Report::getCompanyName, companyName)
                   .orderByDesc(Report::getCreatedAt)
                   .last("LIMIT 1");

            Report report = reportMapper.selectOne(wrapper);
            if (report == null) {
                return "（未找到「" + companyName + "」的历史报告）";
            }

            // 截取前2000字，避免上下文过长
            String content = report.getContent();
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "\n...(内容过长，已截断)";
            }

            return "历史报告（" + report.getCompanyName() + "，生成时间：" + report.getCreatedAt() + "）：\n" + content;
        } catch (Exception e) {
            log.error("[ReadReportTool] 读取历史报告失败: {}", e.getMessage());
            return "（读取历史报告失败：" + e.getMessage() + "）";
        }
    }
}
