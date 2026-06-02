package com.competitor.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTaskRequest {

    @NotBlank(message = "公司名称不能为空")
    @Size(max = 100, message = "公司名称最长100个字符")
    private String companyName;
}
