package com.competitor.agent.framework;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AgentPipeline {

    private final List<AgentStep> steps = new ArrayList<>();
    private PipelineListener listener;

    public AgentPipeline add(Agent agent, int maxRetries, boolean skipOnFailure) {
        steps.add(new AgentStep(agent, maxRetries, skipOnFailure));
        return this;
    }

    public AgentPipeline listener(PipelineListener listener) {
        this.listener = listener;
        return this;
    }

    public PipelineResult execute(AgentContext context) {
        List<AgentResult> agentResults = new ArrayList<>();
        int totalAiCallCount = 0;
        String degradeInfo = null;

        log.info("[Pipeline开始] taskId={} steps={}", context.getTaskId(), steps.size());

        for (AgentStep step : steps) {
            String agentName = step.getAgent().getName();
            log.info("[Pipeline步骤] taskId={} agent={} maxRetries={} skipOnFailure={}",
                    context.getTaskId(), agentName, step.getMaxRetries(), step.isSkipOnFailure());

            if (listener != null) {
                listener.onAgentStart(context.getTaskId(), agentName);
            }

            AgentResult result = executeWithRetry(step, context);
            totalAiCallCount += extractAiCallCount(result);
            agentResults.add(result);

            if (!result.isSuccess()) {
                if (step.isSkipOnFailure()) {
                    log.warn("[Pipeline降级] taskId={} agent={} 失败但跳过，标注降级",
                            context.getTaskId(), agentName);
                    degradeInfo = agentName + "执行失败，结果未经审核";
                    context.getOutputs().put(agentName, result);
                } else {
                    log.error("[Pipeline中断] taskId={} agent={} 失败且不可跳过",
                            context.getTaskId(), agentName);

                    if (listener != null) {
                        listener.onAgentComplete(context.getTaskId(), agentName, false);
                    }

                    PipelineResult failResult = PipelineResult.fail(agentName, result.getError(), agentResults, totalAiCallCount);
                    if (listener != null) {
                        listener.onPipelineComplete(context.getTaskId(), failResult);
                    }
                    return failResult;
                }
            } else {
                context.getOutputs().put(agentName, result);
                log.info("[Pipeline步骤完成] taskId={} agent={} success=true",
                        context.getTaskId(), agentName);
            }

            if (listener != null) {
                listener.onAgentComplete(context.getTaskId(), agentName, result.isSuccess());
            }
        }

        PipelineResult pipelineResult;
        if (degradeInfo != null) {
            pipelineResult = PipelineResult.degraded(degradeInfo, agentResults, totalAiCallCount);
        } else {
            pipelineResult = PipelineResult.success(agentResults, totalAiCallCount);
        }

        if (listener != null) {
            listener.onPipelineComplete(context.getTaskId(), pipelineResult);
        }

        log.info("[Pipeline完成] taskId={} totalAiCallCount={}", context.getTaskId(), totalAiCallCount);
        return pipelineResult;
    }

    private AgentResult executeWithRetry(AgentStep step, AgentContext context) {
        Exception lastException = null;

        for (int i = 0; i <= step.getMaxRetries(); i++) {
            try {
                AgentResult result = step.getAgent().execute(context);
                if (result.isSuccess()) {
                    return result;
                }
                if (i < step.getMaxRetries()) {
                    long backoffMs = (1L << i) * 1000; // 指数退避: 1s, 2s, 4s...
                    log.warn("[Agent重试] agent={} retry={}/{} backoff={}ms error={}",
                            step.getAgent().getName(), i + 1, step.getMaxRetries(), backoffMs, result.getError());
                    Thread.sleep(backoffMs);
                }
                lastException = new RuntimeException(result.getError());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Agent重试中断] agent={}", step.getAgent().getName());
                return AgentResult.fail("重试被中断: " + e.getMessage());
            } catch (Exception e) {
                lastException = e;
                if (i < step.getMaxRetries()) {
                    long backoffMs = (1L << i) * 1000;
                    log.warn("[Agent重试] agent={} retry={}/{} backoff={}ms exception={}",
                            step.getAgent().getName(), i + 1, step.getMaxRetries(), backoffMs, e.getMessage());
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return AgentResult.fail("重试被中断");
                    }
                }
            }
        }

        String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
        log.error("[Agent失败] agent={} 重试{}次后仍失败 error={}",
                step.getAgent().getName(), step.getMaxRetries(), errorMsg);
        return AgentResult.fail("重试" + step.getMaxRetries() + "次后仍失败: " + errorMsg);
    }

    private int extractAiCallCount(AgentResult result) {
        if (result.getData() != null && result.getData().containsKey("aiCallCount")) {
            Object count = result.getData().get("aiCallCount");
            if (count instanceof Number) {
                return ((Number) count).intValue();
            }
        }
        return 0;
    }

    public void clear() {
        steps.clear();
    }
}
