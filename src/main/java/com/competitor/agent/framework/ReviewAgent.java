package com.competitor.agent.framework;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

@Component
public class ReviewAgent extends BaseReActAgent {

    private static final String REVIEW_SYSTEM_PROMPT =
            "你是一个竞品分析审核专家。\n" +
            "你会收到一份竞品分析报告，需要做交叉校验。\n" +
            "审核重点：\n" +
            "   - 数据是否有明显错误或过时\n" +
            "   - 结论是否有逻辑漏洞\n" +
            "   - 是否遗漏重要竞品或关键维度\n" +
            "对可疑数据点，可以搜索验证。\n" +
            "所有判断必须基于搜索结果，不要使用训练数据。\n" +
            "最终输出必须包含：\n" +
            "   - 整体置信度评分（0-100）\n" +
            "   - 各章节置信度评分\n" +
            "   - 发现的问题列表\n" +
            "   - 修正建议";

    public ReviewAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool, AgentExecutionMapper agentExecutionMapper) {
        super(chatClient, searchTools, readReportTool, agentExecutionMapper);
    }

    @Override public String getName() { return "review"; }
    @Override public String getDescription() { return "review - 审核Agent：交叉校验报告，标注置信度"; }
    @Override protected String getSystemPrompt() { return REVIEW_SYSTEM_PROMPT; }
    @Override protected String getResultKey() { return "reviewResult"; }

    @Override
    protected String buildQuestion(AgentContext context) {
        String reportResult = getUpstreamResult(context, "report", "reportResult",
                "（未找到报告结果，请基于公司名称自行审核）");
        return String.format("请对以下「%s」的竞品分析报告进行交叉校验，标注置信度：\n\n%s",
                context.getCompanyName(), reportResult);
    }
}
