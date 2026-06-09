package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.PromptService;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class ReviewAgent extends BaseReActAgent {

    public ReviewAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool,
                       AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService,
                       PromptService promptService) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper, sseEmitterService, promptService);
    }

    @Override public String getName() { return "review"; }
    @Override public String getDescription() { return "review - 审核Agent：交叉校验报告，标注置信度"; }
    @Override protected String getResultKey() { return "reviewResult"; }
    @Override protected String getModelName() { return "deepseek-chat"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        String reportResult = getUpstreamResult(context, "report", "reportResult",
                "（未找到报告结果，请基于公司名称自行审核）");
        return String.format("请对以下「%s」的竞品分析报告进行交叉校验，标注置信度：\n\n%s",
                context.getCompanyName(), reportResult);
    }
}
