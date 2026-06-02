package com.competitor.agent.framework;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class PipelineResult {

    private boolean success;

    private String failedAgent;

    private String errorMessage;

    private List<AgentResult> agentResults = new ArrayList<>();

    private int totalAiCallCount;

    private boolean degraded;

    private String degradeInfo;

    public static PipelineResult success(List<AgentResult> agentResults, int totalAiCallCount) {
        PipelineResult result = new PipelineResult();
        result.setSuccess(true);
        result.setAgentResults(agentResults);
        result.setTotalAiCallCount(totalAiCallCount);
        return result;
    }

    public static PipelineResult fail(String failedAgent, String errorMessage, List<AgentResult> agentResults, int totalAiCallCount) {
        PipelineResult result = new PipelineResult();
        result.setSuccess(false);
        result.setFailedAgent(failedAgent);
        result.setErrorMessage(errorMessage);
        result.setAgentResults(agentResults);
        result.setTotalAiCallCount(totalAiCallCount);
        return result;
    }

    public static PipelineResult degraded(String degradeInfo, List<AgentResult> agentResults, int totalAiCallCount) {
        PipelineResult result = new PipelineResult();
        result.setSuccess(true);
        result.setDegraded(true);
        result.setDegradeInfo(degradeInfo);
        result.setAgentResults(agentResults);
        result.setTotalAiCallCount(totalAiCallCount);
        return result;
    }
}
