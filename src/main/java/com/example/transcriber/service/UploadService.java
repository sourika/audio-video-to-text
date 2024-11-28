package com.example.transcriber.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class UploadService {
    private final WebSocketService webSocketService;
    private final StatusService statusService;
    private final FileCleanupService fileCleanupService;
    @Value("${tempDirPath}")
    private String tempDirPath;


    public UploadService(WebSocketService webSocketService, StatusService statusService,
                         FileCleanupService fileCleanupService) {
        this.webSocketService = webSocketService;
        this.statusService = statusService;
        this.fileCleanupService = fileCleanupService;
    }

    public Mono<String> saveUploadedFile(FilePart filePart, String username, String taskId) {
        log.info("Uploading file: {}", filePart.filename());
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss"));

        // Сначала запускаем очистку старых директорий (асинхронно)
        return fileCleanupService.cleanupOldDirectories()
                .doOnSuccess(unused -> log.info("Cleanup of old directories completed successfully"))
                .onErrorResume(error -> {
                    log.error("Error during cleanup of old directories", error);
                    return Mono.error(new RuntimeException("Failed to clean up old directories", error));
                })
                .then(createTargetPathForUser(username, timestamp)) // Создаем новую директорию и сохраняем файл
                .flatMap(targetDirectory -> {
                    String originalFileName = filePart.filename(); // Получаем оригинальное имя файла
                    String prefixedFileName = "file-" + originalFileName; // Добавляем префикс "file-"
                    Path targetPath = targetDirectory.resolve(prefixedFileName); // Сохраняем файл с префиксом и оригинальным расширением

                    return filePart.transferTo(targetPath)
                            .thenReturn(targetPath.toString())
                            .doOnSuccess(path -> log.info("File upload completed for Task ID: {}", taskId));
                })
                .onErrorResume(e -> {
                    String errorMessage = "Error uploading file: " + filePart.filename();
                    log.error(errorMessage, e);

                    return Mono.when(
                                    webSocketService.sendErrorMessage(username, "Error uploading file. Please try again."),
                                    statusService.updateTaskStatus(taskId, "Error")
                            )
                            .then(Mono.error(new RuntimeException("Error uploading file", e)));
                });
    }

    private Mono<Path> createTargetPathForUser(String username, String timestamp) {
        String directoryPathStr = tempDirPath + File.separator + username + "-" + timestamp;
        Path directoryPath = Paths.get(directoryPathStr);

        return Mono.fromCallable(() -> {
                Files.createDirectories(directoryPath);
                log.debug("Directories created at: {}", directoryPath);
            return directoryPath;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}









