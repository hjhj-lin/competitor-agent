package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.PromptService;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class CollectAgent extends BaseReActAgent {

    public CollectAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool,
                        AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService,
                        PromptService promptService) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper, sseEmitterService, promptService);
    }

    @Override public String getName() { return "collect"; }
    @Override public String getDescription() { return "collect - 采集Agent：从公开渠道采集竞品信息"; }
    @Override protected String getResultKey() { return "collectResult"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        return String.format("请采集「%s」的竞品信息，包括：1.公司简介 2.主要竞品 3.最新动态", context.getCompanyName());
    }
}
