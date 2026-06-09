package com.competitor.agent.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.competitor.agent.entity.PromptTemplate;
import com.competitor.agent.mapper.PromptTemplateMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Prompt模板服务 - 从数据库读取Prompt，Caffeine缓存加速
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptTemplateMapper promptTemplateMapper;

    /** agentName -> PromptTemplate 缓存，5分钟过期 */
    private final Cache<String, PromptTemplate> promptCache = Caffeine.newBuilder()
            .expireAfterWrite(5, java.util.concurrent.TimeUnit.MINUTES)
            .maximumSize(100)
            .build();

    /**
     * 获取Agent的System Prompt
     * 优先从缓存读取，缓存未命中则查库
     */
    public String getSystemPrompt(String agentName) {
        PromptTemplate template = getTemplate(agentName);
        return template.getSystemPrompt();
    }

    /**
     * 获取Agent的User Prompt模板
     * 模板中的 {companyName}, {collectResult} 等占位符由Agent自行替换
     */
    public String getUserPromptTemplate(String agentName) {
        PromptTemplate template = getTemplate(agentName);
        String userTemplate = template.getUserPromptTemplate();
        return userTemplate != null ? userTemplate : "";
    }

    /**
     * 获取完整的PromptTemplate对象
     */
    public PromptTemplate getTemplate(String agentName) {
        return promptCache.get(agentName, this::loadFromDb);
    }

    /**
     * 清除指定Agent的缓存（热更新时调用）
     */
    public void evictCache(String agentName) {
        promptCache.invalidate(agentName);
        log.info("[Prompt缓存清除] agent={}", agentName);
    }

    /**
     * 清除所有缓存
     */
    public void evictAllCache() {
        promptCache.invalidateAll();
        log.info("[Prompt缓存全部清除]");
    }

    /**
     * 获取所有Prompt模板列表
     */
    public List<PromptTemplate> listAll() {
        return promptTemplateMapper.selectList(new LambdaQueryWrapper<PromptTemplate>()
                .orderByAsc(PromptTemplate::getId));
    }

    /**
     * 更新Prompt模板（热更新）
     */
    public PromptTemplate updatePrompt(String agentName, String systemPrompt, String userPromptTemplate) {
        PromptTemplate template = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>().eq(PromptTemplate::getAgentName, agentName));
        if (template == null) {
            throw new RuntimeException("Agent not found: " + agentName);
        }
        template.setSystemPrompt(systemPrompt);
        template.setUserPromptTemplate(userPromptTemplate);
        template.setVersion(template.getVersion() + 1);
        promptTemplateMapper.updateById(template);

        // 清除缓存，下次读取时重新加载
        evictCache(agentName);
        log.info("[Prompt更新] agent={} version={}", agentName, template.getVersion());
        return template;
    }

    private PromptTemplate loadFromDb(String agentName) {
        log.debug("[Prompt加载] 从数据库加载 agent={}", agentName);
        PromptTemplate template = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>().eq(PromptTemplate::getAgentName, agentName));
        if (template == null) {
            throw new RuntimeException("Prompt template not found for agent: " + agentName);
        }
        return template;
    }
}
