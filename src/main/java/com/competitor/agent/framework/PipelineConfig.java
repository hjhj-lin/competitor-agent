package com.competitor.agent.framework;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.competitor.agent.mapper.AnalysisTaskMapper;
import com.competitor.agent.service.SseEmitterService;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class PipelineConfig {

    private final CollectAgent collectAgent;
    private final AnalyzeAgent analyzeAgent;
    private final ReportAgent reportAgent;
    private final ReviewAgent reviewAgent;
    private final SseEmitterService sseEmitterService;
    private final AnalysisTaskMapper analysisTaskMapper;

    @Bean
    public AgentPipeline competitorAnalysisPipeline() {
        AgentPipeline pipeline = new AgentPipeline();
        pipeline.add(collectAgent, 2, false);
        pipeline.add(analyzeAgent, 2, false);
        pipeline.add(reportAgent, 1, false);
        pipeline.add(reviewAgent, 1, true);

        pipeline.listener(new PipelineListener() {
            @Override
            public void onAgentStart(Long taskId, String agentName) {
                LambdaUpdateWrapper<com.competitor.agent.entity.AnalysisTask> wrapper =
                        new LambdaUpdateWrapper<>();
                wrapper.eq(com.competitor.agent.entity.AnalysisTask::getId, taskId)
                       .set(com.competitor.agent.entity.AnalysisTask::getCurrentAgent, agentName);
                analysisTaskMapper.update(null, wrapper);

                sseEmitterService.sendEvent(taskId, "agent", Map.of(
                        "agent", agentName,
                        "status", "RUNNING"
                ));
            }

            @Override
            public void onAgentComplete(Long taskId, String agentName, boolean success) {
                sseEmitterService.sendEvent(taskId, "agent", Map.of(
                        "agent", agentName,
                        "status", success ? "COMPLETED" : "FAILED"
                ));
            }

            @Override
            public void onPipelineComplete(Long taskId, PipelineResult result) {
                sseEmitterService.sendEvent(taskId, "result", Map.of(
                        "status", result.isSuccess() ? "COMPLETED" : "FAILED",
                        "aiCallCount", result.getTotalAiCallCount()
                ));
            }
        });

        return pipeline;
    }
}
