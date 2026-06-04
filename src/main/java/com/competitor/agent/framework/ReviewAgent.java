package com.competitor.agent.framework;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.competitor.agent.entity.AgentExecution;
import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.tool.SearchTools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 审核Agent - 使用Spring AI Tool Calling替代自研ReAct循环
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewAgent implements Agent {

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

    private final ChatClient chatClient;
    private final SearchTools searchTools;
    private final AgentExecutionMapper agentExecutionMapper;

    @Override
    public String getName() {
        return "review";
    }

    @Override
    public String getDescription() {
        return "review - 审核Agent：交叉校验报告，标注置信度";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String companyName = context.getCompanyName();
        log.info("[Agent开始] agent={} taskId={} company={}", getName(), context.getTaskId(), companyName);
        long start = System.currentTimeMillis();

        AgentExecution execution = new AgentExecution();
        execution.setTaskId(context.getTaskId());
        execution.setAgentName(getName());

        try {
            String reportResult = getReportResult(context);
            execution.setInputData(reportResult);

            String question = String.format(
                    "请对以下「%s」的竞品分析报告进行交叉校验，标注置信度：\n\n%s",
                    companyName, reportResult
            );

            // Spring AI Tool Calling: 自动完成"推理→搜索→观察→继续推理"循环
            String result = chatClient.prompt()
                    .system(REVIEW_SYSTEM_PROMPT)
                    .user(question)
                    .tools(searchTools)
                    .call()
                    .content();

            long duration = System.currentTimeMillis() - start;

            log.info("[Agent结束] agent={} taskId={} success=true duration={}ms",
                    getName(), context.getTaskId(), duration);

            execution.setStatus("COMPLETED");
            execution.setOutputData(result);
            execution.setDurationMs((int) duration);
            agentExecutionMapper.insert(execution);

            return AgentResult.success(Map.of(
                    "companyName", companyName,
                    "reviewResult", result
            ));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Agent失败] agent={} taskId={} duration={}ms error={}", getName(), context.getTaskId(), duration, e.getMessage());

            execution.setStatus("FAILED");
            execution.setErrorMessage(e.getMessage());
            execution.setDurationMs((int) duration);
            agentExecutionMapper.insert(execution);

            return AgentResult.fail(e.getMessage());
        }
    }

    private String getReportResult(AgentContext context) {
        Object reportOutput = context.getOutputs().get("report");
        if (reportOutput instanceof AgentResult agentResult) {
            Object reportResult = agentResult.getData().get("reportResult");
            if (reportResult instanceof String s) {
                return s;
            }
        }
        return "（未找到报告结果，请基于公司名称自行审核）";
    }
}
