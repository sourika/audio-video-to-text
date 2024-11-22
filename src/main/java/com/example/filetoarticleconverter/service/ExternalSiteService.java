package com.example.filetoarticleconverter.service;

import com.example.filetoarticleconverter.dto.ArticleData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
@Data
@Slf4j
public class ExternalSiteService {
    private final WebClient webClient;
    private final String accessToken;
    private final WebSocketService webSocketService;

    private final StatusService statusService;

    @Autowired
    public ExternalSiteService(WebClient webClient,
                               @Value("${TELEGRAPH_ACCESS_TOKEN}") String accessToken,
                               WebSocketService webSocketService, StatusService statusService) {
        this.webClient = webClient;
        this.accessToken = accessToken;
        this.webSocketService = webSocketService;
        this.statusService = statusService;
    }

    public Mono<String> publishArticle(String authorName, ArticleData articleData, String taskId, String username) {
        log.info("Publishing article for author: {}", authorName);
        List<Map<String, Object>> content = formatArticleForExternalSite(articleData);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("access_token", accessToken);
        requestBody.put("title", articleData.getTitle());
        requestBody.put("author_name", authorName);
        requestBody.put("content", content);
        requestBody.put("return_content", true);

        return webClient.post()
                .uri("https://api.telegra.ph/createPage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(requestBody))
                .retrieve()
                .onStatus(httpStatus -> !httpStatus.is2xxSuccessful(), clientResponse -> Mono.error(new RuntimeException("Error: " + clientResponse.statusCode())))
                .bodyToMono(String.class)
                .flatMap(responseBody -> {
                    try {
                        ObjectMapper objectMapper = new ObjectMapper();
                        JsonNode rootNode = objectMapper.readTree(responseBody);
                        if (rootNode.has("result") && rootNode.get("result").has("url")) {
                            String pageUrl = rootNode.get("result").get("url").asText();
                            log.info("Article published for Task ID: {}. Page URL: {}", taskId, pageUrl);
                            return Mono.just(pageUrl);
                        } else {
                            String errorMessage = "Unable to retrieve the page URL from the Telegraph API response.";
                            log.error(errorMessage);
                            return Mono.when(
                                            webSocketService.sendErrorMessage(username, "Unable to retrieve the page URL from the Telegraph API response. Please try again."),
                                            statusService.updateTaskStatus(taskId, "Error")
                                    )
                                    .then(Mono.error(new RuntimeException("Error: " + errorMessage)));
                        }
                    } catch (Exception e) {
                        log.error("Error processing the response", e);
                        return Mono.when(
                                        webSocketService.sendErrorMessage(username, "Error processing the response. Please try again."),
                                        statusService.updateTaskStatus(taskId, "Error"))
                                .then(Mono.error(e));
                    }
                });
    }


    private List<Map<String, Object>> formatArticleForExternalSite(ArticleData articleData) {
        List<Map<String, Object>> contentNodes = new ArrayList<>();
        String[] paragraphs = articleData.getContent().split("\n\n");
        for (String paragraph : paragraphs) {
            Map<String, Object> paragraphNode = new HashMap<>();
            paragraphNode.put("tag", "p");
            paragraphNode.put("children", new String[]{paragraph});
            contentNodes.add(paragraphNode);
        }
        return contentNodes;
    }
}

