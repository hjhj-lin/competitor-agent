package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class ReportAgent extends BaseReActAgent {

    private static final String REPORT_SYSTEM_PROMPT =
            "你是一个竞品分析报告撰写专家。\n" +
            "你会收到分析Agent的结论，需要将其整理为结构化报告。\n" +
            "报告必须包含以下章节：\n" +
            "   - 一、公司概况\n" +
            "   - 二、竞品对比分析\n" +
            "   - 三、优劣势总结\n" +
            "   - 四、市场趋势判断\n" +
            "   - 五、建议与风险提示\n" +
            "如果分析数据不足以生成完整报告，可以搜索补充。\n" +
            "报告必须完整输出，不要截断，不要省略。\n" +
            "所有数据必须来自搜索结果，不要使用训练数据编造。";

    public ReportAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool,
                       AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper, sseEmitterService);
    }

    @Override public String getName() { return "report"; }
    @Override public String getDescription() { return "report - 报告Agent：生成结构化竞品分析报告"; }
    @Override protected String getSystemPrompt() { return REPORT_SYSTEM_PROMPT; }
    @Override protected String getResultKey() { return "reportResult"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        String analysisResult = getUpstreamResult(context, "analyze", "analysisResult",
                "（未找到分析结果，请基于公司名称自行搜索并生成报告）");
        return String.format("基于以下竞品分析结论，为「%s」撰写一份结构化的竞品分析报告：\n\n%s",
                context.getCompanyName(), analysisResult);
    }
}
