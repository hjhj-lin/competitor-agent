package com.competitor.agent.framework;

import lombok.Data;

@Data
public class ReActStep {

    private int iteration;

    private String thought;

    private String action;

    private String actionInput;

    private String observation;
}
