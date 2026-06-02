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
public class CollectAgent implements Agent {

    private static final String COLLECT_SYSTEM_PROMPT =
            "你是一个竞品信息采集专家，使用ReAct（推理+行动）模式工作。\n\n" +
            "每一轮你必须严格按照以下格式回复：\n\n" +
            "Thought: [你在想什么，分析当前情况]\n" +
            "Action: [你要执行的动作]\n" +
            "Action Input: [动作的参数]\n\n" +
            "可选的Action：\n" +
            "- search_web: 搜索互联网获取信息（参数：搜索关键词）\n" +
            "- finish: 信息已经足够，输出最终结论（参数：完整的采集结果）\n\n" +
            "重要规则：\n" +
            "1. 严格遵循Thought/Action/Action Input三行格式\n" +
            "2. 每轮只执行一个Action\n" +
            "3. 你需要采集以下维度的信息：公司简介、主要竞品及对比、最新动态（产品/融资/人事变动）\n" +
            "4. 每个维度分别搜索一次，确保信息全面\n" +
            "5. 所有信息必须来自搜索结果，不要使用你的训练数据编造\n" +
            "6. 搜索时加上年份（如\"比亚迪 2026年 销量\"），获取最新数据\n" +
            "7. 当信息足够时，必须使用finish动作\n" +
            "8. finish的Action Input就是你的完整采集结果，要包含所有搜索到的信息，并标注数据来源";

    private final ReActExecutor reActExecutor;
    private final AgentExecutionMapper agentExecutionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "collect";
    }

    @Override
    public String getDescription() {
        return "collect - 采集Agent：使用ReAct模式从公开渠道采集竞品信息";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String companyName = context.getCompanyName();
        log.info("[Agent开始] agent={} taskId={} company={}", getName(), context.getTaskId(), companyName);
        long start = System.currentTimeMillis();

        AgentExecution execution = new AgentExecution();
        execution.setTaskId(context.getTaskId());
        execution.setAgentName(getName());
        execution.setInputData(companyName);

        try {
            String question = String.format(
                    "请采集「%s」的竞品信息，包括：1.公司简介 2.主要竞品 3.最新动态",
                    companyName
            );

            ReActResult reActResult = reActExecutor.execute(COLLECT_SYSTEM_PROMPT, question);

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
                        "collectResult", reActResult.getFinalAnswer(),
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

    private String serializeSteps(java.util.List<ReActStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            log.warn("[序列化steps失败] error={}", e.getMessage());
            return "[]";
        }
    }
}