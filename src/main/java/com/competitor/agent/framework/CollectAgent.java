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
 * 采集Agent - 使用Spring AI Tool Calling替代自研ReAct循环
 * ChatClient会自动完成"推理→调用工具→观察结果→继续推理"的循环
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectAgent implements Agent {

    private static final String COLLECT_SYSTEM_PROMPT =
            "你是一个竞品信息采集专家。\n" +
            "你需要采集以下维度的信息：公司简介、主要竞品及对比、最新动态（产品/融资/人事变动）。\n" +
            "每个维度分别搜索一次，确保信息全面。\n" +
            "所有信息必须来自搜索结果，不要使用你的训练数据编造。\n" +
            "搜索时加上年份（如\"比亚迪 2026年 销量\"），获取最新数据。\n" +
            "当信息足够时，直接输出完整的采集结果，并标注数据来源。";

    private final ChatClient chatClient;
    private final SearchTools searchTools;
    private final AgentExecutionMapper agentExecutionMapper;

    @Override
    public String getName() {
        return "collect";
    }

    @Override
    public String getDescription() {
        return "collect - 采集Agent：从公开渠道采集竞品信息";
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

            // Spring AI Tool Calling: 自动完成"推理→搜索→观察→继续推理"循环
            String result = chatClient.prompt()
                    .system(COLLECT_SYSTEM_PROMPT)
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
                    "collectResult", result
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
}
