package com.competitor.agent.framework;

import java.util.Map;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.competitor.agent.entity.AgentExecution;
import com.competitor.agent.mapper.AgentExecutionMapper;
import com.competitor.agent.service.PromptService;
import com.competitor.agent.service.SseEmitterService;
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
    protected final SseEmitterService sseEmitterService;
    protected final PromptService promptService;

    @Autowired(required = false)
    @Qualifier("kimiChatClient")
    protected ChatClient kimiChatClient;

    protected BaseReActAgent(ChatClient chatClient,
                             SearchTools searchTools, ReadReportTool readReportTool,
                             AgentExecutionMapper agentExecutionMapper, SseEmitterService sseEmitterService,
                             PromptService promptService) {
        this.chatClient = chatClient;
        this.searchTools = searchTools;
        this.readReportTool = readReportTool;
        this.agentExecutionMapper = agentExecutionMapper;
        this.sseEmitterService = sseEmitterService;
        this.promptService = promptService;
    }

    /** 获取当前Agent使用的ChatClient */
    protected ChatClient getActiveChatClient() {
        return useKimi() && kimiChatClient != null ? kimiChatClient : chatClient;
    }

    /** 是否使用Kimi模型，子类可覆盖 */
    protected boolean useKimi() {
        return false;
    }

    @Override
    @CircuitBreaker(name = "aiCall", fallbackMethod = "executeFallback")
    public AgentResult execute(AgentContext context) {
        String companyName = context.getCompanyName();
        Long taskId = context.getTaskId();
        log.info("[Agent开始] agent={} taskId={} company={} model={} provider={}",
                getName(), taskId, companyName, getModelName(), useKimi() ? "kimi" : "deepseek");
        long start = System.currentTimeMillis();

        AgentExecution execution = new AgentExecution();
        execution.setTaskId(taskId);
        execution.setAgentName(getName());

        try {
            String question = buildQuestion(context);
            execution.setInputData(question);

            // 流式调用：逐token推SSE，同时收集完整结果
            StringBuilder resultBuilder = new StringBuilder();

            var promptSpec = getActiveChatClient().prompt()
                    .system(getSystemPrompt())
                    .user(question)
                    .tools(searchTools, readReportTool);

            // 指定模型：覆盖默认模型配置
            String modelName = getModelName();
            if (modelName != null && !modelName.isBlank()) {
                promptSpec = promptSpec.options(OpenAiChatOptions.builder()
                        .model(modelName)
                        .build());
            }

            promptSpec.stream()
                    .content()
                    .doOnNext(token -> {
                        resultBuilder.append(token);
                        // 逐token推SSE给前端
                        sseEmitterService.sendEvent(taskId, "content",
                                Map.of("agent", getName(), "token", token));
                    })
                    .blockLast(); // 阻塞等待流结束

            String result = resultBuilder.toString();
            long duration = System.currentTimeMillis() - start;
            log.info("[Agent结束] agent={} taskId={} success=true duration={}ms", getName(), taskId, duration);

            execution.setStatus("COMPLETED");
            execution.setOutputData(result);
            execution.setDurationMs((int) duration);
            agentExecutionMapper.insert(execution);

            return AgentResult.success(Map.of("companyName", companyName, getResultKey(), result));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Agent失败] agent={} taskId={} duration={}ms error={}", getName(), taskId, duration, e.getMessage());

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

    /** 从PromptService读取System Prompt（数据库配置，支持热更新） */
    protected String getSystemPrompt() {
        return promptService.getSystemPrompt(getName());
    }

    /** 返回Agent使用的模型名，null表示使用默认模型 */
    protected String getModelName() {
        return null;
    }

    protected abstract String buildQuestion(AgentContext context);
    protected abstract String getResultKey();

    /** AI调用熔断降级：直接返回失败结果 */
    private AgentResult executeFallback(AgentContext context, Exception e) {
        log.warn("[AI熔断降级] agent={} taskId={} error={}", getName(), context.getTaskId(), e.getMessage());
        return AgentResult.fail("AI服务暂不可用: " + e.getMessage());
    }
}
