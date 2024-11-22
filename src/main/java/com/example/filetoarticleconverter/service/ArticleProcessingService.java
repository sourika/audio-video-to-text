package com.example.filetoarticleconverter.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Data
@Slf4j
public class ArticleProcessingService {

    private final FileProcessingService fileProcessingService;
    private final OpenAIService openAIService;
    private final ExternalSiteService externalSiteService;
    private final StatusService statusService;
    private final WebSocketService webSocketService;
    private final AudioToTextService audioToTextService;


    public ArticleProcessingService(FileProcessingService fileProcessingService,
                                    OpenAIService openAIService,
                                    ExternalSiteService externalSiteService,
                                    StatusService statusService,
                                    AudioToTextService audioToTextService,
                                    WebSocketService webSocketService) {
        this.fileProcessingService = fileProcessingService;
        this.openAIService = openAIService;
        this.externalSiteService = externalSiteService;
        this.statusService = statusService;
        this.audioToTextService = audioToTextService;
        this.webSocketService = webSocketService;
    }

    public Mono<Void> generateArticle(String targetPath, String authorName, String taskId, String username) {

        // Asynchronous processing chain
        return fileProcessingService.processFileForExtraction(targetPath, taskId, username)
                .flatMap(sourceFilePath ->
                        webSocketService.sendMessage(username, "STATUS: Transcribing...")
                                .then(statusService.updateTaskStatus(taskId, "Transcribing"))
                                .thenReturn(sourceFilePath)
                )
                .flatMap(sourceFilePath ->
                        audioToTextService.convertSpeechToText(sourceFilePath, taskId, username)
                )
                .flatMap(transcription ->
                        webSocketService.sendMessage(username, "STATUS: Generating article...")
                                .then(statusService.updateTaskStatus(taskId, "Generating article"))
                                .thenReturn(transcription)
                )
                .flatMap(promptText ->
                        openAIService.writeArticleWithOpenAi(promptText, taskId, username)
                )
                .flatMap(articleData ->
                        webSocketService.sendMessage(username, "STATUS: Publishing article...")
                                .then(statusService.updateTaskStatus(taskId, "Publishing article"))
                                .then(externalSiteService.publishArticle(authorName, articleData, taskId, username))
                )
                .flatMap(pageUrl ->
                        Mono.when(webSocketService.sendMessage(username, pageUrl),
                                        statusService.updateTaskStatus(taskId, "Completed"))
                                .then(statusService.setRedirectUrl(taskId, pageUrl))
                )
                .then() // Преобразование результата в Mono<Void>
                .onErrorResume(error -> {
                    log.error("Error publishing article for Task ID: {}", taskId, error);
                    return Mono.error(new RuntimeException("Error publishing article", error));
                });
    }
}

