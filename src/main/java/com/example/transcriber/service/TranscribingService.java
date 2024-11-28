package com.example.transcriber.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Slf4j
public class TranscribingService {

    private final FileProcessingService fileProcessingService;
    private final StatusService statusService;
    private final WebSocketService webSocketService;
    private final AudioToTextService audioToTextService;
    private final TranscriptionSaveService transcriptionSaveService;


    public TranscribingService(FileProcessingService fileProcessingService,
                               StatusService statusService,
                               AudioToTextService audioToTextService,
                               WebSocketService webSocketService,
                               TranscriptionSaveService transcriptionSaveService) {
        this.fileProcessingService = fileProcessingService;
        this.statusService = statusService;
        this.audioToTextService = audioToTextService;
        this.webSocketService = webSocketService;
        this.transcriptionSaveService = transcriptionSaveService;
    }

    public Mono<Void> createTranscriptionDoc(String targetPath, String taskId, String username) {

        Path targetPathObj = Paths.get(targetPath);
        String originalFileName = targetPathObj.getFileName().toString();
        Path sourceDirectory = targetPathObj.getParent();

        // Asynchronous processing chain
        return fileProcessingService.processFileForExtraction(targetPath, taskId, username)
                .flatMap(sourceFilePath ->
                        webSocketService.sendMessage(username, "STATUS: Transcribing...")
                                .then(statusService.updateTaskStatus(taskId, "Transcribing"))
                                .then(audioToTextService.convertSpeechToText(sourceFilePath, taskId, username))
                )
                .flatMap(transcription ->
                        webSocketService.sendMessage(username, "STATUS: Saving transcription...")
                                .then(statusService.updateTaskStatus(taskId, "Saving transcription"))
                                .then(transcriptionSaveService.saveTranscriptionToDoc(transcription, originalFileName, taskId, sourceDirectory))
                )
                .flatMap(fileInfo -> {
                    // Создаем URL для скачивания
                    String downloadUrl = String.format("/download-transcription/%s/%s/%s",
                            sourceDirectory.getFileName().toString(), taskId, fileInfo);

                    // Отправляем сообщение с URL через WebSocket
                    return webSocketService.sendMessage(username, "DOWNLOAD:" + downloadUrl);
                })
                .then()
                .onErrorResume(error -> {
                    log.error("Error processing file for Task ID: {}", taskId, error);
                    return Mono.error(new RuntimeException("Error processing file", error));
                });
    }


}

