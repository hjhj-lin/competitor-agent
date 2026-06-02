package com.competitor.agent.framework;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.competitor.agent.entity.AgentExecution;
import com.competitor.agent.mapper.AgentExecutionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeAgent implements Agent {

    private static final String ANALYZE_SYSTEM_PROMPT =
            "你是一个竞品分析专家，使用ReAct（推理+行动）模式工作。\n\n" +
            "每一轮你必须严格按照以下格式回复：\n\n" +
            "Thought: [你在想什么，分析当前情况]\n" +
            "Action: [你要执行的动作]\n" +
            "Action Input: [动作的参数]\n\n" +
            "可选的Action：\n" +
            "- search_web: 搜索互联网补充信息（参数：搜索关键词）\n" +
            "- finish: 分析完成，输出最终结论（参数：完整的分析结果）\n\n" +
            "重要规则：\n" +
            "1. 严格遵循Thought/Action/Action Input三行格式\n" +
            "2. 你会收到采集Agent的原始数据，需要从中提炼关键指标\n" +
            "3. 重点分析：市场地位对比、产品优劣势、技术差异、用户口碑\n" +
            "4. 如果采集数据不够充分，可以搜索补充\n" +
            "5. 所有数据必须来自搜索结果，不要使用训练数据编造\n" +
            "6. 当分析足够深入时，必须使用finish动作\n" +
            "7. finish的Action Input就是你的完整分析结论";

    private final ReActExecutor reActExecutor;
    private final AgentExecutionMapper agentExecutionMapper;
    private final ObjectMapper objectMapper;

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

            ReActResult reActResult = reActExecutor.execute(ANALYZE_SYSTEM_PROMPT, question);

            long duration = System.currentTimeMillis() - start;

            if (reActResult.isSuccess()) {
                log.info("[Agent结束] agent={} taskId={} success=true duration={}ms iterations={} aiCalls={}",
                        getName(), context.getTaskId(), duration,
                        reActResult.getSteps().size(), reActResult.getAiCallCount());

                execution.setStatus("COMPLETED");
                execution.setOutputData(reActResult.getFinalAnswer());
                execution.setDurationMs((int) duration);
                execution.setAiCallCount(reActResult.getAiCallCount());
                execution.setSteps(serializeSteps(reActResult.getSteps()));
                agentExecutionMapper.insert(execution);

                return AgentResult.success(Map.of(
                        "companyName", companyName,
                        "analysisResult", reActResult.getFinalAnswer(),
                        "iterations", reActResult.getSteps().size(),
                        "aiCallCount", reActResult.getAiCallCount()
                ));
            } else {
                log.warn("[Agent结束] agent={} taskId={} success=false duration={}ms error={}",
                        getName(), context.getTaskId(), duration, reActResult.getError());

                execution.setStatus("FAILED");
                execution.setErrorMessage(reActResult.getError());
                execution.setDurationMs((int) duration);
                execution.setAiCallCount(reActResult.getAiCallCount());
                execution.setSteps(serializeSteps(reActResult.getSteps()));
                agentExecutionMapper.insert(execution);

                return AgentResult.fail(reActResult.getError());
            }
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

    private String serializeSteps(java.util.List<ReActStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            log.warn("[序列化steps失败] error={}", e.getMessage());
            return "[]";
        }
    }
}
