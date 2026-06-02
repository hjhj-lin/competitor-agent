package com.competitor.agent.framework;

public interface Agent {

    String getName();

    String getDescription();

    AgentResult execute(AgentContext context);
}
