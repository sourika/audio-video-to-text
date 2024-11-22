package com.example.filetoarticleconverter.service;

import com.example.filetoarticleconverter.dto.IndexedText;
import com.example.filetoarticleconverter.dto.TranscriptionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.filetoarticleconverter.dto.IndexedText.getIndexFromFileName;


@Service
@Data
@Slf4j
public class AudioToTextService {

    private final WebClient webClient;
    private final WebSocketService webSocketService;

    private final StatusService statusService;
    private final String openAiApiKey;
    private final ObjectMapper objectMapper;
    private final FileProcessingService fileProcessingService;


    @Autowired
    public AudioToTextService(WebClient webClient, WebSocketService webSocketService,
                              StatusService statusService,
                              @Value("${OPENAI_API_KEY}") String openAiApiKey,
                              ObjectMapper objectMapper,
                              FileProcessingService fileProcessingService) {
        this.webClient = webClient;
        this.webSocketService = webSocketService;
        this.statusService = statusService;
        this.openAiApiKey = openAiApiKey;
        this.objectMapper = objectMapper;
        this.fileProcessingService = fileProcessingService;
    }

    public Mono<String> convertSpeechToText(String fullFilePath, String taskId, String username) {
        log.info("Transcribing audio from file: {}", fullFilePath);
        Path filePath = Paths.get(fullFilePath);
        return Mono.fromCallable(() -> Files.size(filePath))
                .subscribeOn(Schedulers.boundedElastic()) // Using a scheduler for blocking operations
                .flatMap(fileSizeInBytes -> {
                    long maxSizeInBytes = 25 * 1024 * 1024; // 25 MB

                    if (fileSizeInBytes <= maxSizeInBytes) {
                        return processSmallFile(filePath, taskId, username);
                    } else {
                        return processLargeFile(fullFilePath, filePath, taskId, username);
                    }
                })
                .doOnNext(text -> log.info("Transcription completed for Task ID: {}", taskId))
                .onErrorResume(e -> {
                    log.error("Failed to convert audio from file: {}", fullFilePath, e);
                    return webSocketService.sendErrorMessage(username, "Error converting audio file. Please try again.")
                            .then(statusService.updateTaskStatus(taskId, "Error"))
                            .then(Mono.error(e));
                });
    }

    public Mono<String> processSmallFile(Path filePath, String taskId, String username) {
        return sendFileToOpenAI(filePath, taskId, username)
                .flatMap(jsonResponse -> Mono.fromCallable(() ->
                                objectMapper.readValue(jsonResponse, TranscriptionResponse.class).getText())
                        .onErrorMap(IOException.class, e -> new RuntimeException("Failed to parse response from OpenAI", e)))
                .onErrorResume(e -> {
                    String errorMessage = "Error parsing response from OpenAI. Please try again.";
                    log.error("Failed to parse response from OpenAI for file: {}", filePath, e);
                    return webSocketService.sendErrorMessage(username, errorMessage)
                            .then(statusService.updateTaskStatus(taskId, "Error"))
                            .then(Mono.error(e));
                });
    }


    public Mono<String> processLargeFile(String fullFilePath, Path filePath, String taskId, String username) {
        String tempDirPath = filePath.getParent().toString();
        int splitLengthInSeconds = 20 * 60; // TODO 10 minutes можно ставить 20 минут

        return Mono.fromRunnable(() -> {
                    try {
                        fileProcessingService.splitFile(fullFilePath, tempDirPath, splitLengthInSeconds, username);
                        log.info("File splitting completed successfully.");
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt(); // Restoring the interrupt status
                        }
                        throw new RuntimeException("Error during file splitting", e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.defer(() ->
                        // Using Flux.defer in combination with Flux.using to dynamically and lazily create a Flux from a list of files in the specified directory
                        Flux.using(() -> Files.list(Paths.get(tempDirPath)),
                                        paths -> Flux.fromStream(paths
                                                .filter(path -> !Files.isDirectory(path) && !path.getFileName().toString().startsWith("file"))),
                                        Stream::close) // Closing the Stream to avoid resource leaks
                                .subscribeOn(Schedulers.boundedElastic()) // Blocking operation to perform file reading on the boundedElastic scheduler
                ))
                .flatMap(path -> {
                    final int index;
                    try {
                        index = getIndexFromFileName(path);  // Получаем индекс файла заранее
                    } catch (IllegalArgumentException e) {
                        log.warn("Failed to extract index from filename: {}. Defaulting to 0.", path.getFileName(), e);
                        return Mono.just(new IndexedText(0, ""));
                    }
                    log.debug("Processing split file with index {}: {}", index, path);
                    return processSmallFile(path, taskId, username)
                            .map(text -> new IndexedText(index, text)); // Используем полученный индекс
                })
                .collectSortedList(Comparator.comparing(IndexedText::getIndex))
                .map(sortedList -> sortedList.stream()
                        .map(IndexedText::getText)
                        .collect(Collectors.joining(" ")))
                .doOnSuccess(text -> log.info("File splitting and transcription completed for Task ID: {}", taskId))
                .onErrorResume(e -> {
                    log.error("Error during file splitting and transcription for Task ID: {}", taskId, e);
                    return webSocketService.sendErrorMessage(username, "Error processing large audio file. Please try again.")
                            .then(statusService.updateTaskStatus(taskId, "Error"))
                            .then(Mono.error(e));
                });
    }


    public Mono<String> sendFileToOpenAI(Path filePath, String taskId, String username) {
        log.info("Sending a file to OpenAI: {}", filePath);
        Resource fileResource = new FileSystemResource(filePath.toFile());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("model", "whisper-1");

        return webClient.post()
                .uri("https://api.openai.com/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
                .body(BodyInserters.fromMultipartData(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> {
                    String errorMessage = "Failed to transcribe audio with status code: " + response.statusCode();
                    log.error(errorMessage);

                    return webSocketService.sendErrorMessage(username, "Error transcribing audio file from OpenAI. Please try again.")
                            .then(statusService.updateTaskStatus(taskId, "Error"))
                            .then(Mono.error(new RuntimeException(errorMessage)));
                })
                .bodyToMono(String.class)
                .doOnSuccess(response -> log.info("Successful audio file transcription: {}", filePath))
                .onErrorResume(e -> {
                    log.error("Error transcribing audio file from OpenAI: {}", filePath, e);
                    return webSocketService.sendErrorMessage(username, "Error transcribing audio file from OpenAI. Please try again.")
                            .then(statusService.updateTaskStatus(taskId, "Error"))
                            .then(Mono.error(e));
                });
    }
}

