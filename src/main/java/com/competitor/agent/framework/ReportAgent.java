package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.PromptService;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class ReportAgent extends BaseReActAgent {

    public ReportAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool,
                       AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService,
                       PromptService promptService) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper, sseEmitterService, promptService);
    }

    @Override public String getName() { return "report"; }
    @Override public String getDescription() { return "report - 报告Agent：生成结构化竞品分析报告"; }
    @Override protected String getResultKey() { return "reportResult"; }
    @Override protected String getModelName() { return "deepseek-chat"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        String analysisResult = getUpstreamResult(context, "analyze", "analysisResult",
                "（未找到分析结果，请基于公司名称自行搜索并生成报告）");
        return String.format("基于以下竞品分析结论，为「%s」撰写一份结构化的竞品分析报告：\n\n%s",
                context.getCompanyName(), analysisResult);
    }
}
