package com.competitor.agent.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("prompt_template")
public class PromptTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Agent名称，如 collect/analyze/report/review */
    private String agentName;

    /** Prompt描述 */
    private String description;

    /** System Prompt内容 */
    private String systemPrompt;

    /** User Prompt模板，支持 {companyName} 占位符 */
    private String userPromptTemplate;

    /** 版本号，每次更新+1 */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
