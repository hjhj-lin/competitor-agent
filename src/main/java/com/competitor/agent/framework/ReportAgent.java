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
public class ReportAgent implements Agent {

    private static final String REPORT_SYSTEM_PROMPT =
            "你是一个竞品分析报告撰写专家，使用ReAct（推理+行动）模式工作。\n\n" +
            "每一轮你必须严格按照以下格式回复：\n\n" +
            "Thought: [你在想什么，分析当前情况]\n" +
            "Action: [你要执行的动作]\n" +
            "Action Input: [动作的参数]\n\n" +
            "可选的Action：\n" +
            "- search_web: 搜索互联网补充数据（参数：搜索关键词）\n" +
            "- finish: 报告撰写完成，输出最终报告（参数：完整的结构化报告）\n\n" +
            "重要规则：\n" +
            "1. 严格遵循Thought/Action/Action Input三行格式\n" +
            "2. 你会收到分析Agent的结论，需要将其整理为结构化报告\n" +
            "3. 报告必须包含以下章节：\n" +
            "   - 一、公司概况\n" +
            "   - 二、竞品对比分析\n" +
            "   - 三、优劣势总结\n" +
            "   - 四、市场趋势判断\n" +
            "   - 五、建议与风险提示\n" +
            "4. 如果分析数据不足以生成完整报告，可以搜索补充\n" +
            "5. 报告必须完整输出，不要截断，不要省略\n" +
            "6. 所有数据必须来自搜索结果，不要使用训练数据编造\n" +
            "7. 当报告足够完整时，必须使用finish动作\n" +
            "8. finish的Action Input就是你的完整结构化报告";

    private final ReActExecutor reActExecutor;
    private final AgentExecutionMapper agentExecutionMapper;
    private final ObjectMapper objectMapper;

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

            ReActResult reActResult = reActExecutor.execute(REPORT_SYSTEM_PROMPT, question);

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
                        "reportResult", reActResult.getFinalAnswer(),
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

    private String serializeSteps(java.util.List<ReActStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            log.warn("[序列化steps失败] error={}", e.getMessage());
            return "[]";
        }
    }
}
