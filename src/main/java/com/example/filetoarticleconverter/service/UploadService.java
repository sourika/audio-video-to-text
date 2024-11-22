package com.example.filetoarticleconverter.service;

import com.example.filetoarticleconverter.service.strategy.UrlConverterStrategy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@Data
@Slf4j
public class UploadService {
    private final WebSocketService webSocketService;

    private final StatusService statusService;
    private final WebClient webClient;
    @Value("${tempDirPath}")
    private String tempDirPath;

    private final List<UrlConverterStrategy> urlConverters;


    public UploadService(WebSocketService webSocketService, StatusService statusService,
                         WebClient webClient, List<UrlConverterStrategy> urlConverters) {
        this.webSocketService = webSocketService;
        this.statusService = statusService;
        this.webClient = webClient;
        this.urlConverters = urlConverters;
    }

    public Mono<String> saveUploadedFile(FilePart filePart, String username, String taskId) {
        log.info("Uploading file: {}", filePart.filename());

        return createTargetPathForUser(username)
                .flatMap(targetDirectory -> {
                    String originalFileName = filePart.filename(); // Получаем оригинальное имя файла
                    String prefixedFileName = "file-" + originalFileName; // Добавляем префикс "file-"
                    Path targetPath = targetDirectory.resolve(prefixedFileName); // Сохраняем файл с префиксом и оригинальным расширением

                    return filePart.transferTo(targetPath)
                            .then(Mono.defer(() -> {
                                log.info("File upload completed for Task ID: {}", taskId);
                                return Mono.just(targetPath.toString()); // Возвращаем путь к файлу после его сохранения
                            }));
                })
                .onErrorResume(e -> {
                    String errorMessage = "Error uploading file: " + filePart.filename();
                    log.error(errorMessage, e);
                  return Mono.when(
                          webSocketService.sendErrorMessage(username, "Error uploading file. Please try again."),
                          statusService.updateTaskStatus(taskId, "Error")
                          )
                          .then(Mono.error(new RuntimeException("Error generating article", e)));
                });
    }

    private Mono<Path> createTargetPathForUser(String username) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss");
        String formattedDateTime = now.format(formatter);
        String directoryPathStr = tempDirPath + File.separator + username + "-" + formattedDateTime;
        Path directoryPath = Paths.get(directoryPathStr);

        return Mono.fromCallable(() -> {
            Files.createDirectories(directoryPath);
            log.debug("Directories created at: {}", directoryPath);
            return directoryPath;
        }).subscribeOn(Schedulers.boundedElastic());
    }


    public Mono<String> uploadFileFromUrl(String fileUrl, String taskId, String username) {
        log.info("Uploading file from url: {}", fileUrl);

        // Применяем подходящую стратегию преобразования URL
        String processedUrl = urlConverters.stream()
                .filter(converter -> converter.supports(fileUrl))
                .findFirst()
                .map(converter -> converter.convert(fileUrl))
                .orElse(fileUrl); // Если ни одна стратегия не поддерживает URL, используем оригинальный URL

        log.debug("Converted fileUrl: {}", processedUrl);
        return createTargetPathForUser(username)
                .flatMap(targetDir -> webClient.get()
                        .uri(processedUrl)
                        .exchangeToMono(response -> {
                            // Извлечение имени файла из заголовка Content-Disposition
                            String disposition = response.headers().asHttpHeaders().getFirst("Content-Disposition");
                            String filename;
                            String extension = determineFileExtension(response.headers().asHttpHeaders().getFirst("Content-Type"));

                            if (disposition != null && disposition.contains("filename=")) {
                                // Извлекаем имя файла из заголовка
                                filename = disposition.split("filename=")[1].replace("\"", "").trim();
                                filename = "file-" + filename;
                                log.debug("Filename from Content-Disposition: {}", filename);
                            } else {
                                // Извлечение из URL или генерация
                                filename = extractFilenameFromUrl(fileUrl);
                                // Добавление расширения, если необходимо
                                if (!filename.contains(".")) {
                                    filename += extension;
                                    log.debug("Added extension: {}", filename);
                                }
                            }

                            Path targetFilePath = targetDir.resolve(filename);
                            log.debug("Attempting to write to targetFilePath: {}", targetFilePath);
                            // Running asynchronously
                            return response.bodyToFlux(DataBuffer.class)
                                    .flatMap(dataBuffer ->
                                            DataBufferUtils.write(Flux.just(dataBuffer), targetFilePath,
                                                            StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                                                    .doOnSuccess(unused -> {
                                                        DataBufferUtils.release(dataBuffer); // Освобождение после успешной записи
                                                        log.debug("DataBuffer released after success.");
                                                    })
                                                    .doOnError(e -> {
                                                        DataBufferUtils.release(dataBuffer); // Освобождение в случае ошибки
                                                        log.debug("DataBuffer released after error.");
                                                    }))
                                    .then(Mono.defer(() -> {
                                        log.info("File upload completed for Task ID: {}", taskId);
                                        return Mono.just(targetFilePath.toString()); // Возвращаем путь к файлу после его сохранения
                                    }))
                                    .onErrorResume(e -> {
                                        String errorMessage = "Error uploading file from URL: " + processedUrl;
                                        log.error(errorMessage, e);
                                        return Mono.when(
                                                webSocketService.sendErrorMessage(username, "Error uploading file. Please try again."),
                                                statusService.updateTaskStatus(taskId, "Error")
                                                )
                                                .then(Mono.error(new RuntimeException("Error generating article", e)));
                                    });
                        }));

    }


//                        targetPath -> {
//                            // Running asynchronously
//                            return webClient.get()
//                                    .uri(fileUrl)
//                                    .retrieve()
//                                    .bodyToFlux(DataBuffer.class)
//                                    .flatMap(dataBuffer ->
//                                            DataBufferUtils.write(Flux.just(dataBuffer), targetPath,
//                                                            StandardOpenOption.CREATE, StandardOpenOption.WRITE)
//                                                    .doOnSuccess(unused -> DataBufferUtils.release(dataBuffer)) // Освобождение после успешной записи
//                                                    .doOnError(e -> DataBufferUtils.release(dataBuffer))) // Освобождение в случае ошибки
//                                    .then(Mono.defer(() -> {
//                                        log.info("File upload completed for Task ID: {}", taskId);
//                                        return Mono.just(targetPath.toString()); // Возвращаем путь к файлу после его сохранения
//                                    }))
//                                    .doOnError(e -> {
//                                        String errorMessage = "Error uploading file from URL: " + fileUrl;
//                                        log.error(errorMessage, e);
//                                        webSocketService.sendErrorMessage(username, "Error uploading file. Please try again.");
//                                    });
//                        });
//    }
//    }

//    private String convertGoogleDriveUrlToDirectDownload(String fileUrl) {
//        if (fileUrl.contains("drive.google.com")) {
//            try {
//                URL url = new URL(fileUrl);
//                String path = url.getPath(); // /file/d/1Si5DQVsSIaWvZ4qWHGJfvHYDAnmiOZ9_/view
//                String[] segments = path.split("/");
//                int idIndex = Arrays.asList(segments).indexOf("d");
//                if (idIndex != -1 && segments.length > idIndex + 1) {
//                    String fileId = segments[idIndex + 1];
//                    return "https://drive.google.com/uc?export=download&id=" + fileId;
//                }
//            } catch (MalformedURLException e) {
//                log.error("Invalid Google Drive URL: {}", fileUrl, e);
//            }
//        }
//        return fileUrl; // Возвращаем оригинальный URL, если это не Google Drive
//    }


    private String extractFilenameFromUrl(String fileUrl) {
        try {
            URL url = new URL(fileUrl);
            String path = url.getPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            // Удаление параметров запроса, если они есть
            if (filename.contains("?")) {
                filename = filename.substring(0, filename.indexOf('?'));
            }
            // Если имя файла пусто, генерируем уникальное имя
            if (filename.isEmpty()) {
                filename = "file-" + UUID.randomUUID();
            } else {
                filename = "file-" + filename;
            }
            return filename;
        } catch (MalformedURLException e) {
            log.error("Invalid URL: {}", fileUrl, e);
            // Генерация уникального имени файла с префиксом, если URL некорректен
            return "file-" + UUID.randomUUID();
        }
    }


    private String determineFileExtension(String contentType) {
        if (contentType == null) {
            return "";
        }
        return switch (contentType) {
            case "audio/mpeg" -> ".mp3";
            case "audio/wav" -> ".wav";
            case "audio/mp4" -> ".m4a";
            case "audio/flac" -> ".flac";
            case "audio/ogg" -> ".ogg";
            case "audio/amr" -> ".amr";
            case "audio/aiff" -> ".aiff";
            case "audio/x-ms-wma" -> ".wma";
            default -> "";
        };
    }
}



