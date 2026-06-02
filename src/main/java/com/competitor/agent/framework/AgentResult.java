package com.competitor.agent.framework;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class AgentResult {

    private boolean success;

    private Map<String, Object> data = new HashMap<>();

    private String error;

    private double confidence;


    public static AgentResult success(Map<String, Object> data) {
        AgentResult result = new AgentResult();
        result.setSuccess(true);
        result.setData(data);
        return result;
    }

    public static AgentResult fail(String error) {
        AgentResult result = new AgentResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }
}
