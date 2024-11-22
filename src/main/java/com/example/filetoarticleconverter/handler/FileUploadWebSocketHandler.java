package com.example.filetoarticleconverter.handler;

import com.example.filetoarticleconverter.service.ArticleProcessingService;
import com.example.filetoarticleconverter.service.StatusService;
import com.example.filetoarticleconverter.service.UploadService;
import com.example.filetoarticleconverter.service.WebSocketService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class FileUploadWebSocketHandler implements WebSocketHandler {

    private final UploadService uploadService;
    private final ArticleProcessingService articleProcessingService;
    private final WebSocketService webSocketService;
    private final StatusService statusService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public FileUploadWebSocketHandler(UploadService uploadService,
                                      ArticleProcessingService articleProcessingService,
                                      WebSocketService webSocketService,
                                      StatusService statusService) {
        this.uploadService = uploadService;
        this.articleProcessingService = articleProcessingService;
        this.webSocketService = webSocketService;
        this.statusService = statusService;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String query = session.getHandshakeInfo().getUri().getQuery();
        String username = query != null && query.startsWith("username=") ? query.substring("username=".length()) : null;
        log.info("User connected: " + username);

        if (username == null || username.isEmpty()) {
            log.error("User information is not available in the WebSocket session.");
            return session.close(CloseStatus.BAD_DATA.withReason("User information is missing"));
        }

        // Сохраняем сессию
        webSocketService.addSession(username, session);

        return session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(message -> processMessage(message, username))
                .onErrorResume(e -> {
                    log.error("Error processing WebSocket message for user {}: {}", username, e.getMessage());
                  return webSocketService.sendErrorMessage(username, "Internal server error. Please try again.");
                })
                .doFinally(signalType -> {
                    // Удаляем сессию при завершении
                    webSocketService.removeSession(username);
                    log.info("User disconnected: " + username);
                })
                .then(); // Завершаем всю цепочку

    }

    private Mono<Void> processMessage(String message, String username) {
        // Парсим сообщение
        Map<String, String> payload;
        try {
            payload = mapper.readValue(message, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            log.error("Error parsing WebSocket message: {}", message, e);
            return webSocketService.sendErrorMessage(username, "Invalid message format.");
        }

        String fileUrl = payload.get("fileUrl");
        String authorName = payload.get("authorName");

        if (fileUrl != null && !fileUrl.isBlank()) {
            // Обработка файла по URL
            String taskId = UUID.randomUUID().toString();
            log.info("Received request to generate article. Task ID: {}, URL: {}, Author Name: {}", taskId, fileUrl, authorName);
           return Mono.when(webSocketService.sendMessage(username, "Uploading file..."),
                   statusService.updateTaskStatus(taskId, "Uploading file"))
                    .then(uploadService.uploadFileFromUrl(fileUrl, taskId, username))
                    .flatMap(sourceFilePath -> articleProcessingService.generateArticle(sourceFilePath, authorName, taskId, username))
//                    .doOnSuccess(result -> {
//                        log.info("Article processing completed successfully for Task ID: {}", taskId);
//                        webSocketService.sendMessage(username, "Article generated successfully.");
//                        statusService.updateTaskStatus(taskId, "Completed");
//                    })
                    .onErrorResume(error -> {
                        log.error("Error processing article for Task ID: {}", taskId, error);
                      return Mono.when(webSocketService.sendErrorMessage(username, "Error processing article. Please try again."),
                        statusService.updateTaskStatus(taskId, "Error"));
                    });
        } else {
            log.error("No URL provided.");
          return webSocketService.sendErrorMessage(username, "No URL provided. Please try again.");
        }
    }
}
