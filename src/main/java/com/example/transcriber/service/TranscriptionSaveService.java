package com.example.transcriber.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Service
@Slf4j
@RequiredArgsConstructor
public class TranscriptionSaveService {

    public Mono<String> saveTranscriptionToDoc(String transcription, String originalFileName, String taskId, Path sourceDirectory) {
        log.info("Starting to save transcription document. Task ID: {}, Original file: {}", taskId, originalFileName);

        log.info("Text: {}", transcription);

        String fileNameWithoutExt = originalFileName.replaceFirst("[.][^.]+$", "");
        String docFileName = fileNameWithoutExt + "_transcription.doc";
        Path filePath = sourceDirectory.resolve(docFileName);
        log.info("Путь к файлу: {}", filePath);
        log.info("Имя файла: {}", docFileName);

        return Mono.using(
                        () -> AsynchronousFileChannel.open(filePath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                        channel -> {
                            ByteBuffer buffer = ByteBuffer.wrap(transcription.getBytes());
                            return Mono.fromCallable(() -> channel.write(buffer, 0))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doOnSuccess(bytesWritten -> log.debug("Successfully wrote {} bytes to file: {}", bytesWritten, filePath));
                        },
                        channel -> {
                            try {
                                channel.close();
                            } catch (IOException e) {
                                log.error("Error closing channel", e);
                            }
                        }
                )
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(aVoid -> log.info("Successfully saved transcription document. Task ID: {}, File: {}", taskId, docFileName))
                .doOnError(error -> log.error("Error saving transcription document for Task ID: {}: {}", taskId, error.getMessage()))
                .thenReturn(docFileName);
    }
}




