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
 * 报告Agent - 使用Spring AI Tool Calling替代自研ReAct循环
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAgent implements Agent {

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

    private final ChatClient chatClient;
    private final SearchTools searchTools;
    private final AgentExecutionMapper agentExecutionMapper;

    @Override
    public String getName() {
        return "report";
    }

    @Override
    public String getDescription() {
        return "report - 报告Agent：生成结构化竞品分析报告";
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
            String analysisResult = getAnalysisResult(context);
            execution.setInputData(analysisResult);

            String question = String.format(
                    "基于以下竞品分析结论，为「%s」撰写一份结构化的竞品分析报告：\n\n%s",
                    companyName, analysisResult
            );

            // Spring AI Tool Calling: 自动完成"推理→搜索→观察→继续推理"循环
            String result = chatClient.prompt()
                    .system(REPORT_SYSTEM_PROMPT)
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
                    "reportResult", result
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

    private String getAnalysisResult(AgentContext context) {
        Object analyzeOutput = context.getOutputs().get("analyze");
        if (analyzeOutput instanceof AgentResult agentResult) {
            Object analysisResult = agentResult.getData().get("analysisResult");
            if (analysisResult instanceof String s) {
                return s;
            }
        }
        return "（未找到分析结果，请基于公司名称自行搜索并生成报告）";
    }
}
