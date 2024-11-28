package com.example.transcriber.controller;

import com.example.transcriber.service.TranscribingService;
import com.example.transcriber.service.StatusService;
import com.example.transcriber.service.UploadService;
import com.example.transcriber.service.WebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@Slf4j
public class FileUploadController {

    private final UploadService uploadService;
    private final StatusService statusService;
    private final TranscribingService transcribingService;
    private final WebSocketService webSocketService;

    @Value("${tempDirPath}")
    private String tempDirPath;

    @Autowired
    public FileUploadController(UploadService uploadService, StatusService statusService,
                                TranscribingService transcribingService,
                                WebSocketService webSocketService) {
        this.uploadService = uploadService;
        this.statusService = statusService;
        this.transcribingService = transcribingService;
        this.webSocketService = webSocketService;
    }

    @PostMapping("/upload-file")
    public Mono<ResponseEntity<Void>> handleFileUpload(@RequestPart("file") FilePart filePart,
                                                       @RequestHeader("username") String username) { // передаем username через заголовок)
        String taskId = UUID.randomUUID().toString();
        log.info("Received request to create transcription document. Task ID: {}, File: {}", taskId, filePart.filename());
        return Mono.when(webSocketService.sendMessage(username, "STATUS: Uploading file..."),
                        statusService.updateTaskStatus(taskId, "Uploading file"))
                .then(uploadService.saveUploadedFile(filePart, username, taskId))
                .flatMap(sourceFilePath ->
                        transcribingService.createTranscriptionDoc(sourceFilePath, taskId, username)
                )
                .flatMap(docPath ->
                        Mono.when(
                                webSocketService.sendMessage(username, "DOWNLOAD:" + docPath),
                                statusService.updateTaskStatus(taskId, "Completed")
                        )
                )
                .doOnSuccess(unused ->
                        log.info("Creating transcription document completed successfully for Task ID: {}", taskId)
                )
                .then(Mono.just(new ResponseEntity<Void>(HttpStatus.OK)))
                .onErrorResume(e -> {
                    log.error("Error creating transcription document for Task ID: {}", taskId, e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }

    @GetMapping("/download-transcription/{sourceDirectory}/{taskId}/{filename}")
    public Mono<ResponseEntity<Resource>> downloadTranscription(@PathVariable String sourceDirectory,
                                                                @PathVariable String taskId,
                                                                @PathVariable String filename) {
        log.info("Received request to download transcription. Directory: {}, TaskID: {}, File: {}",
                sourceDirectory, taskId, filename);

        Path directoryPath = Paths.get(tempDirPath, sourceDirectory);
        Path filePath = directoryPath.resolve(filename);

        if (!Files.exists(filePath)) {
            log.error("Requested file does not exist: {}", filePath);
            return Mono.just(ResponseEntity.notFound().build());
        }

        return Mono.fromCallable(() -> {
                    Resource resource = new FileSystemResource(filePath.toFile());
                    return resource;
                })
                .map(resource -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                        .header(HttpHeaders.CONTENT_TYPE, "application/msword")
                        .body(resource))
                .doOnSuccess(unused ->
                        log.info("Successfully served transcription file. Task ID: {}, File: {}",
                                taskId, filename)
                )
                .onErrorResume(e -> {
                    log.error("Error serving transcription file for Task ID: {}, File: {}: {}",
                            taskId, filename, e.getMessage());
                    return Mono.just(ResponseEntity.notFound().build());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}







