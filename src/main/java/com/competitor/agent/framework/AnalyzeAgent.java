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
 * 分析Agent - 使用Spring AI Tool Calling替代自研ReAct循环
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeAgent implements Agent {

    private static final String ANALYZE_SYSTEM_PROMPT =
            "你是一个竞品分析专家。\n" +
            "你会收到采集Agent的原始数据，需要从中提炼关键指标。\n" +
            "重点分析：市场地位对比、产品优劣势、技术差异、用户口碑。\n" +
            "如果采集数据不够充分，可以搜索补充。\n" +
            "所有数据必须来自搜索结果，不要使用训练数据编造。\n" +
            "当分析足够深入时，直接输出完整的分析结论。";

    private final ChatClient chatClient;
    private final SearchTools searchTools;
    private final AgentExecutionMapper agentExecutionMapper;

    @Override
    public String getName() {
        return "analyze";
    }

    @Override
    public String getDescription() {
        return "analyze - 分析Agent：提炼关键指标，对比竞品优劣势";
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
            String collectResult = getCollectResult(context);
            execution.setInputData(collectResult);

            String question = String.format(
                    "基于以下采集数据，对「%s」进行竞品分析，提炼关键指标，对比竞品优劣势：\n\n%s",
                    companyName, collectResult
            );

            // Spring AI Tool Calling: 自动完成"推理→搜索→观察→继续推理"循环
            String result = chatClient.prompt()
                    .system(ANALYZE_SYSTEM_PROMPT)
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
                    "analysisResult", result
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

    private String getCollectResult(AgentContext context) {
        Object collectOutput = context.getOutputs().get("collect");
        if (collectOutput instanceof AgentResult agentResult) {
            Object collectResult = agentResult.getData().get("collectResult");
            if (collectResult instanceof String s) {
                return s;
            }
        }
        return "（未找到采集结果，请基于公司名称自行搜索分析）";
    }
}
