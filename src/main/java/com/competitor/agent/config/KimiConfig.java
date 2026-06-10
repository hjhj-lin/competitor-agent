package com.competitor.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kimi模型配置 - 注册独立的ChatClient Bean
 * Kimi使用OpenAI兼容API，但base-url不同，需要单独的ChatModel
 */
@Configuration
public class KimiConfig {

    @Bean
    @ConditionalOnProperty(name = "kimi.api-key", matchIfMissing = false)
    public ChatClient kimiChatClient(
            @Value("${kimi.api-key}") String apiKey,
            @Value("${kimi.base-url}") String baseUrl,
            @Value("${kimi.model}") String model) {

        OpenAiApi kimiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        OpenAiChatModel kimiChatModel = OpenAiChatModel.builder()
                .openAiApi(kimiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .maxTokens(4096)
                        .temperature(0.7)
                        .build())
                .build();

        return ChatClient.builder(kimiChatModel).build();
    }
}
