package com.competitor.agent.framework;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class AgentContext {

    private Long taskId;

    private String companyName;

    private Map<String, Object> inputs = new HashMap<>();

    private Map<String, Object> outputs = new ConcurrentHashMap<>();

    private List<Message> history = new ArrayList<>();

    public void addUserMessage(String content) {
        history.add(new UserMessage(content));
    }

    public static AgentContext of(Long taskId, String companyName) {
        AgentContext context = new AgentContext();
        context.setTaskId(taskId);
        context.setCompanyName(companyName);
        return context;
    }
}
