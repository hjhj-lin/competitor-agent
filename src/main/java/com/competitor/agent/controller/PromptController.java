package com.competitor.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.competitor.agent.common.Result;
import com.competitor.agent.entity.PromptTemplate;
import com.competitor.agent.service.PromptService;

import lombok.RequiredArgsConstructor;

/**
 * Prompt模板管理API - 支持查看和热更新
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    /** 获取所有Prompt模板列表 */
    @GetMapping
    public Result<List<PromptTemplate>> listPrompts() {
        return Result.success(promptService.listAll());
    }

    /** 获取指定Agent的Prompt模板 */
    @GetMapping("/{agentName}")
    public Result<PromptTemplate> getPrompt(@PathVariable String agentName) {
        return Result.success(promptService.getTemplate(agentName));
    }

    /** 热更新Prompt模板（无需重启） */
    @PutMapping("/{agentName}")
    public Result<PromptTemplate> updatePrompt(
            @PathVariable String agentName,
            @RequestBody Map<String, String> body) {
        String systemPrompt = body.get("systemPrompt");
        String userPromptTemplate = body.get("userPromptTemplate");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return Result.error(400, "systemPrompt不能为空");
        }
        PromptTemplate updated = promptService.updatePrompt(agentName, systemPrompt, userPromptTemplate);
        return Result.success(updated);
    }
}
