package com.competitor.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TavilyService {

    @Value("${tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${tavily.base-url:https://api.tavily.com}")
    private String tavilyBaseUrl;

    @Value("${tavily.timeout-seconds:10}")
    private int tavilyTimeoutSeconds;

    @Value("${spring.ai.openai.api-key}")
    private String deepseekApiKey;

    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";

    public String search(String query) {
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            return searchWithTavily(query);
        }
        log.info("[Tavily未配置，使用DeepSeek搜索] query={}", query);
        return searchWithDeepSeek(query);
    }

    private String searchWithTavily(String query) {
        try {
            log.info("[Tavily搜索] query={}", query);
            long start = System.currentTimeMillis();

            String requestBody = String.format(
                    "{\"api_key\":\"%s\",\"query\":\"%s\",\"max_results\":3,\"include_answer\":true}",
                    escapeJson(tavilyApiKey), escapeJson(query)
            );

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(tavilyTimeoutSeconds))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tavilyBaseUrl + "/search"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(tavilyTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                String result = parseTavilyResult(response.body());
                log.info("[Tavily成功] duration={}ms resultLength={}", duration, result.length());
                return result;
            } else {
                log.warn("[Tavily失败] statusCode={} duration={}ms, 降级到DeepSeek搜索", response.statusCode());
                return searchWithDeepSeek(query);
            }
        } catch (Exception e) {
            log.warn("[Tavily超时/异常] error={}，降级到DeepSeek搜索", e.getMessage());
            return searchWithDeepSeek(query);
        }
    }

    private String parseTavilyResult(String responseBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> json = mapper.readValue(responseBody, Map.class);

            StringBuilder sb = new StringBuilder();

            Object answer = json.get("answer");
            if (answer != null && !answer.toString().isBlank()) {
                sb.append(answer.toString().trim());
            }

            Object results = json.get("results");
            if (results instanceof List) {
                List<Map<String, Object>> resultList = (List<Map<String, Object>>) results;
                for (int i = 0; i < Math.min(3, resultList.size()); i++) {
                    Map<String, Object> item = resultList.get(i);
                    if (sb.length() > 0) sb.append("\n\n");
                    String title = (String) item.getOrDefault("title", "");
                    String content = (String) item.getOrDefault("content", "");
                    if (!title.isBlank()) sb.append("【").append(title).append("】");
                    if (!content.isBlank()) {
                        String truncated = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                        sb.append(truncated);
                    }
                }
            }

            return sb.length() > 0 ? sb.toString() : "未找到相关搜索结果";
        } catch (Exception e) {
            log.warn("[Tavily解析失败] error={}", e.getMessage());
            return "搜索结果解析失败";
        }
    }

    private String searchWithDeepSeek(String query) {
        try {
            log.info("[DeepSeek联网搜索] query={}", query);
            long start = System.currentTimeMillis();

            String requestBody = String.format(
                    "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":" +
                            "\"请联网搜索「%s」的最新信息，包括官方资料、财务数据、最新动态等。只返回搜索到的事实，用200字以内回答。\"}]," +
                            "\"enable_search\":true,\"max_tokens\":500,\"temperature\":0.7}",
                    escapeJson(query)
            );

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEEPSEEK_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + deepseekApiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                String result = parseDeepSeekResponse(response.body());
                log.info("[DeepSeek搜索成功] duration={}ms resultLength={}", duration, result.length());
                return result;
            } else {
                log.warn("[DeepSeek搜索失败] statusCode={} duration={}ms body={}", response.statusCode(), duration, response.body().substring(0, Math.min(200, response.body().length())));
                return "搜索失败：" + response.statusCode();
            }
        } catch (Exception e) {
            log.error("[DeepSeek搜索异常] error={}", e.getMessage());
            return "搜索异常：" + e.getMessage();
        }
    }

    private String parseDeepSeekResponse(String responseBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> json = mapper.readValue(responseBody, Map.class);

            List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> firstChoice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
                if (message != null) {
                    return (String) message.getOrDefault("content", "");
                }
            }

            return "解析失败";
        } catch (Exception e) {
            log.warn("[DeepSeek响应解析失败] error={}", e.getMessage());
            return "响应解析失败";
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
