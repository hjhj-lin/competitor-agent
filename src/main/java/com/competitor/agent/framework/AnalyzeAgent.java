package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.PromptService;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class AnalyzeAgent extends BaseReActAgent {

    public AnalyzeAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool,
                        AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService,
                        PromptService promptService) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper, sseEmitterService, promptService);
    }

    @Override public String getName() { return "analyze"; }
    @Override public String getDescription() { return "analyze - 分析Agent：提炼关键指标，对比竞品优劣势"; }
    @Override protected String getResultKey() { return "analysisResult"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        String collectResult = getUpstreamResult(context, "collect", "collectResult",
                "（未找到采集结果，请基于公司名称自行搜索分析）");
        return String.format("基于以下采集数据，对「%s」进行竞品分析，提炼关键指标，对比竞品优劣势：\n\n%s",
                context.getCompanyName(), collectResult);
    }
}
