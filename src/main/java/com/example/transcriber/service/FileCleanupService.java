package com.example.transcriber.service;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class FileCleanupService {

    @Value("${tempDirPath}")
    private String tempDirPath;

    public Mono<Void> cleanupOldDirectories() {
        log.info("Starting cleanup of old directories");

        return Mono.fromCallable(() -> {
                    Path baseDir = Paths.get(tempDirPath);
                    if (!Files.exists(baseDir)) {
                        log.info("Base directory does not exist: {}", baseDir);
                        return List.<Path>of();  // возвращаем пустой список, если директория не существует
                    }

                    LocalDateTime threshold = LocalDateTime.now().minus(1, ChronoUnit.DAYS);

                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
                        return StreamSupport.stream(stream.spliterator(), false)
                                .filter(Files::isDirectory)
                                .filter(path -> !path.equals(baseDir)) // пропускаем базовую директорию
                                .filter(path -> isDirectoryOlderThanThreshold(path, threshold))
                                .collect(Collectors.toList());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(this::deleteDirectory)
                .then();
    }


    private boolean isDirectoryOlderThanThreshold(Path path, LocalDateTime threshold) {
        try {
            String dirName = path.getFileName().toString();
            if (dirName.length() >= 19) { // Проверяем, что длина имени достаточна для парсинга даты
                String dateTimePart = dirName.substring(dirName.length() - 19);
                LocalDateTime dirDateTime = LocalDateTime.parse(dateTimePart,
                        DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss"));
                return dirDateTime.isBefore(threshold);
            } else {
                throw new DateTimeParseException("Invalid directory name format", dirName, 0);
            }
        } catch (DateTimeParseException e) {
            log.warn("Could not parse directory name: {}", path, e);
            throw e; // Пробрасываем исключение, чтобы явно указать на ошибку
        }
    }

    private Mono<Void> deleteDirectory(Path path) {
        return Mono.fromRunnable(() -> {
                    try (Stream<Path> pathStream = Files.walk(path)) {
                        pathStream
                                .sorted(Comparator.reverseOrder()) // сначала удаляем содержимое, потом саму директорию
                                .forEach(p -> {
                                    try {
                                        Files.delete(p);
                                        log.debug("Deleted: {}", p);
                                    } catch (FileSystemException e) {
                                        log.error("Error deleting path: {}. Reason: {}", p, e.getReason(), e);
                                        throw new RuntimeException("Error deleting path due to file system issue: " + p, e);
                                    } catch (IOException e) {
                                        log.error("Error deleting path: {}. File might be used by another process or permission is denied.", p, e);
                                        throw new RuntimeException("Error deleting path: " + p, e); // Пробрасываем ошибку дальше
                                    }
                                });
                        log.info("Successfully deleted directory: {}", path);
                    } catch (IOException e) {
                        log.error("Error walking through directory: {}", path, e);
                        throw new RuntimeException("Error walking through directory: " + path, e); // Прерываем обход при ошибке
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .then()
                .doOnError(error -> log.error("Error while deleting directory: {}", error.getMessage()));
    }
}
