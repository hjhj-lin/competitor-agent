package com.competitor.agent.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private final ObjectMapper objectMapper;

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(Long taskId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitters.put(taskId, emitter);

        emitter.onCompletion(() -> {
            log.info("[SSE完成] taskId={}", taskId);
            emitters.remove(taskId);
        });
        emitter.onTimeout(() -> {
            log.info("[SSE超时] taskId={}", taskId);
            emitters.remove(taskId);
        });
        emitter.onError(e -> {
            log.warn("[SSE错误] taskId={} error={}", taskId, e.getMessage());
            emitters.remove(taskId);
        });

        return emitter;
    }

    public void sendEvent(Long taskId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }
        try {
            String jsonData = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(jsonData, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("[SSE发送失败] taskId={} event={} error={}", taskId, eventName, e.getMessage());
            emitters.remove(taskId);
        }
    }

    public void completeEmitter(Long taskId) {
        SseEmitter emitter = emitters.remove(taskId);
        if (emitter != null) {
            emitter.complete();
        }
    }
}
