package com.example.filetoarticleconverter.controller;

import com.example.filetoarticleconverter.service.ArticleProcessingService;
import com.example.filetoarticleconverter.service.StatusService;
import com.example.filetoarticleconverter.service.UploadService;
import com.example.filetoarticleconverter.service.WebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@Slf4j
public class FileUploadController {

    private final UploadService uploadService;
    private final StatusService statusService;
    private final ArticleProcessingService articleProcessingService;
    private final WebSocketService webSocketService;

    @Autowired
    public FileUploadController(UploadService uploadService, StatusService statusService,
                                ArticleProcessingService articleProcessingService,
                                WebSocketService webSocketService) {
        this.uploadService = uploadService;
        this.statusService = statusService;
        this.articleProcessingService = articleProcessingService;
        this.webSocketService = webSocketService;
    }

    @PostMapping("/upload-file")
    public Mono<ResponseEntity<Void>> handleFileUpload(@RequestPart("file") FilePart filePart,
                                                         @RequestPart("authorName") String authorName,
                                                         @RequestHeader("username") String username) { // передаем username через заголовок)
        String taskId = UUID.randomUUID().toString();
        log.info("Received request to generate article. Task ID: {}, File: {}, Author Name: {}", taskId, filePart.filename(), authorName);
        return Mono.when(webSocketService.sendMessage(username, "STATUS: Uploading file..."),
                        statusService.updateTaskStatus(taskId, "Uploading file"))
                .then(uploadService.saveUploadedFile(filePart, username, taskId))
                .flatMap(sourceFilePath ->
                        articleProcessingService.generateArticle(sourceFilePath, authorName, taskId, username)
                )
                  .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(unused -> log.info("Article processing completed successfully for Task ID: {}", taskId))
                .then(Mono.just(new ResponseEntity<Void>(HttpStatus.OK)))
                .onErrorResume(e -> {
                    log.error("Error processing article for Task ID: {}", taskId, e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }
}



