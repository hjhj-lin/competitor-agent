package com.competitor.agent.vo;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class ReportVO {

    private Long id;

    private Long taskId;

    private String companyName;

    private String content;

    private LocalDateTime createdAt;
}
