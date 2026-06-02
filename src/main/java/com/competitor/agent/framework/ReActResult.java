package com.competitor.agent.framework;

import lombok.Data;
import java.util.List;

@Data
public class ReActResult {

    private boolean success;

    private String finalAnswer;

    private List<ReActStep> steps;

    private int aiCallCount;

    private String error;

    public static ReActResult success(String finalAnswer, List<ReActStep> steps, int aiCallCount) {
        ReActResult result = new ReActResult();
        result.setSuccess(true);
        result.setFinalAnswer(finalAnswer);
        result.setSteps(steps);
        result.setAiCallCount(aiCallCount);
        return result;
    }

    public static ReActResult fail(String error, List<ReActStep> steps, int aiCallCount) {
        ReActResult result = new ReActResult();
        result.setSuccess(false);
        result.setError(error);
        result.setSteps(steps);
        result.setAiCallCount(aiCallCount);
        return result;
    }
}
