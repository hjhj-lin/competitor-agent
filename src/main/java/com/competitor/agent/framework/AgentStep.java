package com.competitor.agent.framework;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentStep {
    private Agent agent;        // 该步骤要执行的 Agent
    private int maxRetries;     // 失败时的最大重试次数
    private boolean skipOnFailure; // 失败后是否跳过继续执行
}
