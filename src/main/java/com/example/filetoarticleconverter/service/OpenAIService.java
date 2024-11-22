package com.example.filetoarticleconverter.service;

import com.example.filetoarticleconverter.dto.ArticleData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;


@Service
@Data
@Slf4j
public class OpenAIService {
    private final WebClient webClient;
    private final WebSocketService webSocketService;

    private final StatusService statusService;
    private final String openAiApiKey;

    @Value("${PROMPT}")
    private String prompt;

    @Autowired
    public OpenAIService(WebClient webClient, WebSocketService webSocketService,
                         StatusService statusService,
                         @Value("${OPENAI_API_KEY}") String openAiApiKey) {
        this.webClient = webClient;
        this.webSocketService = webSocketService;
        this.statusService = statusService;
        this.openAiApiKey = openAiApiKey;
    }

    public Mono<ArticleData> writeArticleWithOpenAi(String promptText, String taskId, String username) {
        String fullPrompt = prompt + " " + promptText;
        log.info("Generating article with prompt: {}", fullPrompt);

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", fullPrompt);

        List<Map<String, Object>> messagesList = new ArrayList<>();
        messagesList.add(message);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4-1106-preview");
        requestBody.put("messages", messagesList);

        return webClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + openAiApiKey)
                .body(BodyInserters.fromValue(requestBody))
                .retrieve()
                .bodyToMono(String.class)
                .map(this::getArticleData)
                .doOnNext(text -> log.info("Article generation completed for Task ID: {}", taskId))
                .onErrorResume(e -> {
                    log.error("Error generating article", e);
                  return Mono.when(
                  webSocketService.sendErrorMessage(username, "Error generating article. Please try again."),
                    statusService.updateTaskStatus(taskId, "Error")
                          )
                    .then(Mono.error(new RuntimeException("Error generating article", e)));
                });
    }

    private ArticleData getArticleData(String response) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode responseJson = objectMapper.readTree(response);
            ArticleData articleData = new ArticleData();
            JsonNode choicesNode = responseJson.path("choices");

            if (!choicesNode.isEmpty() && choicesNode.isArray()) {
                JsonNode firstChoiceNode = choicesNode.get(0);
                JsonNode messageNode = firstChoiceNode.path("message");
                if (!messageNode.isMissingNode()) {
                    String content = messageNode.path("content").asText();
                    String[] lines = content.split("\n\n", 2);
                    if (lines.length >= 2) {
                        articleData.setTitle(lines[0].replace("Title:", "").trim());
                        articleData.setContent(lines[1].replace("Text:", "").trim());
                    }
                }
            }
            return articleData;
        } catch (Exception e) {
            log.error("Failed to parse article data", e);
            throw new RuntimeException("Failed to parse article data", e);
        }
    }
}



