package com.competitor.agent.framework;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;

import com.competitor.agent.entity.AgentExecution;
import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.tool.ReadReportTool;
import com.competitor.agent.tool.SearchTools;

import lombok.extern.slf4j.Slf4j;

/**
 * Agent抽象基类 - 模板方法模式
 * 封装了所有Agent共有的执行逻辑，子类只需实现3个方法：
 *   - getSystemPrompt()：返回角色指令
 *   - buildQuestion()：构建具体问题
 *   - getResultKey()：返回结果存入AgentContext的key名
 */
@Slf4j
public abstract class BaseReActAgent implements Agent {

    protected final ChatClient chatClient;
    protected final SearchTools searchTools;
    protected final ReadReportTool readReportTool;
    protected final AgentExecutionMapper agentExecutionMapper;

    protected BaseReActAgent(ChatClient chatClient, SearchTools searchTools, ReadReportTool readReportTool, AgentExecutionMapper agentExecutionMapper) {
        this.chatClient = chatClient;
        this.searchTools = searchTools;
        this.readReportTool = readReportTool;
        this.agentExecutionMapper = agentExecutionMapper;
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
            String question = buildQuestion(context);
            execution.setInputData(question);

            // Spring AI Tool Calling: 注册搜索工具+历史报告工具
            String result = chatClient.prompt()
                    .system(getSystemPrompt())
                    .user(question)
                    .tools(searchTools, readReportTool)
                    .call()
                    .content();

            long duration = System.currentTimeMillis() - start;
            log.info("[Agent结束] agent={} taskId={} success=true duration={}ms", getName(), context.getTaskId(), duration);

            execution.setStatus("COMPLETED");
            execution.setOutputData(result);
            execution.setDurationMs((int) duration);
            agentExecutionMapper.insert(execution);

            return AgentResult.success(Map.of("companyName", companyName, getResultKey(), result));
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

    /** 从上游Agent的输出中取数据 */
    protected String getUpstreamResult(AgentContext context, String agentName, String resultKey, String fallback) {
        Object output = context.getOutputs().get(agentName);
        if (output instanceof AgentResult agentResult) {
            Object result = agentResult.getData().get(resultKey);
            if (result instanceof String s) {
                return s;
            }
        }
        return fallback;
    }

    protected abstract String getSystemPrompt();
    protected abstract String buildQuestion(AgentContext context);
    protected abstract String getResultKey();
}
