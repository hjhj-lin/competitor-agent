package com.competitor.agent.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("agent_execution")
public class AgentExecution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String agentName;

    private String status;

    private String inputData;

    private String outputData;

    private String errorMessage;

    private String steps;

    private Integer durationMs;

    @TableField(fill = FieldFill.INSERT)
    private Integer aiCallCount = 0;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}