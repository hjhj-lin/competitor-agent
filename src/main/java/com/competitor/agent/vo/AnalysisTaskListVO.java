package com.competitor.agent.vo;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class AnalysisTaskListVO {

    private Long id;

    private String companyName;

    private String status;

    private String currentAgent;

    private Integer aiCallCount;

    private LocalDateTime createdAt;
}
