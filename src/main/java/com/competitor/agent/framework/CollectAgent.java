package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class CollectAgent extends BaseReActAgent {

    private static final String COLLECT_SYSTEM_PROMPT =
            "你是一个竞品信息采集专家。\n" +
            "你需要采集以下维度的信息：公司简介、主要竞品及对比、最新动态（产品/融资/人事变动）。\n" +
            "每个维度分别搜索一次，确保信息全面。\n" +
            "所有信息必须来自搜索结果，不要使用你的训练数据编造。\n" +
            "搜索时加上年份（如\"比亚迪 2026年 销量\"），获取最新数据。\n" +
            "当信息足够时，直接输出完整的采集结果，并标注数据来源。";

    public CollectAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool,
                        AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper, sseEmitterService);
    }

    @Override public String getName() { return "collect"; }
    @Override public String getDescription() { return "collect - 采集Agent：从公开渠道采集竞品信息"; }
    @Override protected String getSystemPrompt() { return COLLECT_SYSTEM_PROMPT; }
    @Override protected String getResultKey() { return "collectResult"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        return String.format("请采集「%s」的竞品信息，包括：1.公司简介 2.主要竞品 3.最新动态", context.getCompanyName());
    }
}
