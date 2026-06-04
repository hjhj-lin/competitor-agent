package com.competitor.agent.tool;

import com.competitor.agent.service.TavilyService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 搜索工具 - 供Spring AI Tool Calling使用
 * 替代原来ReActExecutor中硬编码的search_web Action
 */
@Component
@RequiredArgsConstructor
public class SearchTools {

    private final TavilyService tavilyService;

    @Tool(description = "搜索互联网获取信息，用于查询公司简介、竞品对比、最新动态、财务数据等。参数query为搜索关键词，建议加上年份获取最新数据。")
    public String searchWeb(String query) {
        return tavilyService.search(query);
    }
}
