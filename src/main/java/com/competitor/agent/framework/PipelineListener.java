package com.competitor.agent.framework;

public interface PipelineListener {

    void onAgentStart(Long taskId, String agentName);

    void onAgentComplete(Long taskId, String agentName, boolean success);

    void onPipelineComplete(Long taskId, PipelineResult result);
}
