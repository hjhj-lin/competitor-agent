package com.competitor.agent.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.competitor.agent.service.AiService;
import com.competitor.agent.service.TavilyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReActExecutor {

    private final AiService aiService;
    private final TavilyService tavilyService;

    private static final int MAX_ITERATIONS = 10;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个竞品分析专家，使用ReAct（推理+行动）模式工作。\n\n" +
            "每一轮你必须严格按照以下格式回复：\n\n" +
            "Thought: [你在想什么，分析当前情况]\n" +
            "Action: [你要执行的动作]\n" +
            "Action Input: [动作的参数]\n\n" +
            "可选的Action：\n" +
            "- search_web: 搜索互联网获取信息（参数：搜索关键词）\n" +
            "- finish: 信息已经足够，输出最终结论（参数：完整的分析结果）\n\n" +
            "重要规则：\n" +
            "1. 严格遵循Thought/Action/Action Input三行格式\n" +
            "2. 每轮只执行一个Action\n" +
            "3. 需要不同维度的信息时，分别搜索（如：公司简介、竞品、最新动态各搜一次）\n" +
            "4. 当信息足够时，必须使用finish动作\n" +
            "5. finish的Action Input就是你的最终分析结论";

    private static final Pattern THOUGHT_PATTERN = Pattern.compile("Thought:\\s*(.+?)(?=\\nAction:)", Pattern.DOTALL);
    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(.+?)(?=\\nAction Input:)", Pattern.DOTALL);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile("Action Input:\\s*(.+)", Pattern.DOTALL);

    public ReActResult execute(String systemPrompt, String userQuestion) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userQuestion));

        List<ReActStep> steps = new ArrayList<>();
        int aiCallCount = 0;

        log.info("[ReAct开始] question={}", userQuestion);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("[ReAct轮次] iteration={}/{}", i + 1, MAX_ITERATIONS);

            String aiResponse = aiService.chatWithMessages(messages);
            aiCallCount++;
            messages.add(new AssistantMessage(aiResponse));

            ReActStep step = parseResponse(aiResponse);
            step.setIteration(i + 1);
            steps.add(step);

            log.info("[ReAct解析] iteration={} thought={} action={}", i + 1, step.getThought(), step.getAction());

            if ("finish".equalsIgnoreCase(step.getAction())) {
                log.info("[ReAct完成] iterations={} aiCallCount={}", i + 1, aiCallCount);
                return ReActResult.success(step.getActionInput(), steps, aiCallCount);
            }

            String observation = executeAction(step.getAction(), step.getActionInput());
            step.setObservation(observation);

            String observationText = "Observation: " + observation;
            messages.add(new UserMessage(observationText));

            log.info("[ReAct观察] iteration={} observationLength={}", i + 1, observation.length());
        }

        log.warn("[ReAct超限] 达到最大轮次 {}", MAX_ITERATIONS);
        return ReActResult.fail("达到最大轮次限制(" + MAX_ITERATIONS + ")", steps, aiCallCount);
    }

    public ReActResult execute(String userQuestion) {
        return execute(DEFAULT_SYSTEM_PROMPT, userQuestion);
    }

    private String executeAction(String action, String actionInput) {
        if ("search_web".equalsIgnoreCase(action)) {
            return tavilyService.search(actionInput);
        }

        log.warn("[ReAct未知Action] action={}，降级为搜索", action);
        return tavilyService.search(actionInput);
    }

    ReActStep parseResponse(String response) {
        ReActStep step = new ReActStep();

        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
        if (thoughtMatcher.find()) {
            step.setThought(thoughtMatcher.group(1).trim());
        } else {
            step.setThought("(无法解析Thought)");
        }

        Matcher actionMatcher = ACTION_PATTERN.matcher(response);
        if (actionMatcher.find()) {
            step.setAction(actionMatcher.group(1).trim());
        } else {
            step.setAction("unknown");
        }

        Matcher actionInputMatcher = ACTION_INPUT_PATTERN.matcher(response);
        if (actionInputMatcher.find()) {
            step.setActionInput(actionInputMatcher.group(1).trim());
        } else {
            step.setActionInput("");
        }

        return step;
    }
}
