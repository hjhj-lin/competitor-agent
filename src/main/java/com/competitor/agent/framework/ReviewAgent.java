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
public class ReviewAgent implements Agent {

    private static final String REVIEW_SYSTEM_PROMPT =
            "你是一个竞品分析审核专家，使用ReAct（推理+行动）模式工作。\n\n" +
            "每一轮你必须严格按照以下格式回复：\n\n" +
            "Thought: [你在想什么，分析当前情况]\n" +
            "Action: [你要执行的动作]\n" +
            "Action Input: [动作的参数]\n\n" +
            "可选的Action：\n" +
            "- search_web: 搜索互联网验证数据准确性（参数：搜索关键词）\n" +
            "- finish: 审核完成，输出审核结论（参数：完整的审核结果）\n\n" +
            "重要规则：\n" +
            "1. 严格遵循Thought/Action/Action Input三行格式\n" +
            "2. 你会收到一份竞品分析报告，需要做交叉校验\n" +
            "3. 审核重点：\n" +
            "   - 数据是否有明显错误或过时\n" +
            "   - 结论是否有逻辑漏洞\n" +
            "   - 是否遗漏重要竞品或关键维度\n" +
            "4. 对可疑数据点，可以搜索验证\n" +
            "5. 所有判断必须基于搜索结果，不要使用训练数据\n" +
            "6. 最终输出必须包含：\n" +
            "   - 整体置信度评分（0-100）\n" +
            "   - 各章节置信度评分\n" +
            "   - 发现的问题列表\n" +
            "   - 修正建议\n" +
            "7. 当审核足够完整时，必须使用finish动作\n" +
            "8. finish的Action Input就是你的完整审核结果";

    private final ReActExecutor reActExecutor;
    private final AgentExecutionMapper agentExecutionMapper;
    private final ObjectMapper objectMapper;

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

            ReActResult reActResult = reActExecutor.execute(REVIEW_SYSTEM_PROMPT, question);

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
                        "reviewResult", reActResult.getFinalAnswer(),
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

    private String serializeSteps(java.util.List<ReActStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            log.warn("[序列化steps失败] error={}", e.getMessage());
            return "[]";
        }
    }
}
