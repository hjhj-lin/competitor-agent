package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class AnalyzeAgent extends BaseReActAgent {

    private static final String ANALYZE_SYSTEM_PROMPT =
            "你是一个竞品分析专家。\n" +
            "你会收到采集Agent的原始数据，需要从中提炼关键指标。\n" +
            "重点分析：市场地位对比、产品优劣势、技术差异、用户口碑。\n" +
            "如果采集数据不够充分，可以搜索补充。\n" +
            "所有数据必须来自搜索结果，不要使用训练数据编造。\n" +
            "当分析足够深入时，直接输出完整的分析结论。";

    public AnalyzeAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool,
                        AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper, sseEmitterService);
    }

    @Override public String getName() { return "analyze"; }
    @Override public String getDescription() { return "analyze - 分析Agent：提炼关键指标，对比竞品优劣势"; }
    @Override protected String getSystemPrompt() { return ANALYZE_SYSTEM_PROMPT; }
    @Override protected String getResultKey() { return "analysisResult"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        String collectResult = getUpstreamResult(context, "collect", "collectResult",
                "（未找到采集结果，请基于公司名称自行搜索分析）");
        return String.format("基于以下采集数据，对「%s」进行竞品分析，提炼关键指标，对比竞品优劣势：\n\n%s",
                context.getCompanyName(), collectResult);
    }
}
