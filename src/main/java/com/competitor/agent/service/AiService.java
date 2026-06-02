package com.competitor.agent.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ChatClient chatClient;

    public String chat(String message) {
        log.info("[AI调用] message={}", message);
        long start = System.currentTimeMillis();

        String response = chatClient.prompt()
                .user(message)
                .call()
                .content();

        long duration = System.currentTimeMillis() - start;
        log.info("[AI响应] duration={}ms responseLength={}", duration, response != null ? response.length() : 0);

        return response;
    }

    public String chatWithMessages(List<Message> messages) {
        log.info("[AI调用-多轮] messageCount={}", messages.size());
        long start = System.currentTimeMillis();

        String response = chatClient.prompt()
                .messages(messages.toArray(new Message[0]))
                .call()
                .content();

        long duration = System.currentTimeMillis() - start;
        log.info("[AI响应-多轮] duration={}ms responseLength={}", duration, response != null ? response.length() : 0);

        return response;
    }

    public Flux<String> chatStream(String message) {
        log.info("[AI流式调用] message={}", message);

        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
