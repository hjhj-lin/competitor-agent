package com.competitor.agent.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.competitor.agent.entity.AnalysisTask;
import com.competitor.agent.entity.Report;
import com.competitor.agent.enums.TaskStatus;
import com.competitor.agent.framework.AgentContext;
import com.competitor.agent.framework.AgentPipeline;
import com.competitor.agent.framework.AgentResult;
import com.competitor.agent.framework.PipelineResult;
import com.competitor.agent.mapper.AnalysisTaskMapper;
import com.competitor.agent.mapper.ReportMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineExecutionService {

    private final AgentPipeline agentPipeline;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final ReportMapper reportMapper;
    private final SseEmitterService sseEmitterService;
    private final AiUsageService aiUsageService;

    @Async("pipelineExecutor")
    public void executePipeline(Long taskId, String companyName, Long userId) {
        log.info("[异步执行开始] taskId={} company={}", taskId, companyName);

        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("[异步执行失败] 任务不存在 taskId={}", taskId);
            return;
        }

        AgentContext context = AgentContext.of(taskId, companyName);

        try {
            PipelineResult pipelineResult = agentPipeline.execute(context);

            if (pipelineResult.isSuccess()) {
                String reportContent = extractFromPipeline(pipelineResult, "reportResult");
                String reviewContent = extractFromPipeline(pipelineResult, "reviewResult");

                StringBuilder finalResult = new StringBuilder();
                if (reportContent != null) {
                    finalResult.append(reportContent);
                }
                if (reviewContent != null) {
                    finalResult.append("\n\n---\n\n## 审核意见\n\n").append(reviewContent);
                }

                if (pipelineResult.isDegraded()) {
                    finalResult.append("\n\n⚠️ ").append(pipelineResult.getDegradeInfo());
                    log.warn("[任务降级完成] taskId={} degradeInfo={}", taskId, pipelineResult.getDegradeInfo());
                }

                task.setStatus(TaskStatus.COMPLETED.getCode());
                task.setResult(finalResult.toString());
                task.setAiCallCount(pipelineResult.getTotalAiCallCount());

                // 写入 report 表
                Report report = new Report();
                report.setTaskId(taskId);
                report.setUserId(userId);
                report.setCompanyName(companyName);
                report.setContent(finalResult.toString());
                reportMapper.insert(report);
                log.info("[报告存储] taskId={} reportId={}", taskId, report.getId());
            } else {
                task.setStatus(TaskStatus.FAILED.getCode());
                task.setResult("Pipeline执行失败[" + pipelineResult.getFailedAgent() + "]: " + pipelineResult.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("[Pipeline执行异常] taskId={} error={}", taskId, e.getMessage());
            task.setStatus(TaskStatus.FAILED.getCode());
            task.setResult("Pipeline执行异常: " + e.getMessage());
        }

        analysisTaskMapper.updateById(task);

        // 累加每日AI用量
        if (task.getAiCallCount() != null && task.getAiCallCount() > 0) {
            aiUsageService.addUsage(userId, task.getAiCallCount());
        }

        sseEmitterService.completeEmitter(taskId);
        log.info("[异步执行结束] taskId={} status={}", taskId, task.getStatus());
    }

    private String extractFromPipeline(PipelineResult pipelineResult, String resultKey) {
        for (AgentResult agentResult : pipelineResult.getAgentResults()) {
            if (agentResult.getData() != null) {
                Object value = agentResult.getData().get(resultKey);
                if (value instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }
}
