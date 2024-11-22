package com.example.filetoarticleconverter.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

@Service
@Data
@Slf4j
public class FileProcessingService {

    private final WebSocketService webSocketService;

    private final StatusService statusService;
    @Value("${ffmpegPath}")
    private String ffmpegPath;

    @Value("${ffprobePath}")
    private String ffprobePath;

    @Autowired
    public FileProcessingService(WebSocketService webSocketService, StatusService statusService) {
        this.webSocketService = webSocketService;
        this.statusService = statusService;
    }

    public Mono<String> processFileForExtraction(String targetPath, String taskId, String username) {

        Set<String> directAudioExtensions = Set.of("mp3", "m4a");

        // Получаем расширение файла
        String fileExtension = getFileExtension(targetPath).toLowerCase();
        log.info("Detected file extension: {}", fileExtension);

        if (fileExtension.isEmpty()) {
            log.error("File has no extension: {}", targetPath);
            return Mono.when(
                            webSocketService.sendErrorMessage(username, "The file has no extension. Please select a valid file and try again."),
                            statusService.updateTaskStatus(taskId, "Error")
                    )
                    .then(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "File has no extension. Please try again.")));
        }

        // Check if the file has an audio track
        return Mono.fromCallable(() ->
                        hasAudioTrack(targetPath))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    // Логируем ошибку (опционально)
                    log.error("Error checking for audio track: " + e.getMessage(), e);

                    // Выполняем параллельные операции по отправке сообщения и обновлению статуса
                    return Mono.when(
                                    webSocketService.sendErrorMessage(username, "Error checking for audio track. Please try again."),
                                    statusService.updateTaskStatus(taskId, "Error")
                            )
                            .then(Mono.error(new RuntimeException("Error checking for audio track: " + e.getMessage(), e)));
                })
                .flatMap(hasAudio -> {
                    if (hasAudio) {
                        if (directAudioExtensions.contains(fileExtension)) {
                            return Mono.just(targetPath); // The file has an audio track and is in a direct format
                        } else {
                            // The file has an audio track but is in another format, extract audio
                            return extractAudio(targetPath, taskId, username);
                        }
                    } else {
                        log.error("The file does not contain an audio track: {}", targetPath);
                        return Mono.when(
                                        webSocketService.sendErrorMessage(username, "The file does not contain an audio track. Make sure to upload an appropriate file."),
                                        statusService.updateTaskStatus(taskId, "Error")
                                )
                                .then(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file does not contain an audio track. Make sure to upload an appropriate file.")));
                    }
                });
    }


    // Вспомогательный метод для получения расширения файла
    private String getFileExtension(String filePath) {
        int lastDotIndex = filePath.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filePath.length() - 1) {
            return ""; // Если точка отсутствует или находится в конце строки, расширения нет
        }
        return filePath.substring(lastDotIndex + 1).toLowerCase(); // Возвращаем расширение в нижнем регистре
    }

    // Method to check if the file has an audio track
    private boolean hasAudioTrack(String filePath) throws IOException, InterruptedException {
        if (!isMediaFile(filePath)) {
            log.info("The file is not a media file.");
            return false;
        }

        ProcessBuilder pb = new ProcessBuilder(
                ffprobePath, // Ensure ffprobe is accessible via this path
                "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=codec_type",
                "-of", "default=noprint_wrappers=1:nokey=1",
                filePath
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        boolean hasAudio = false;
        // Read the process output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("audio")) {
                    hasAudio = true;
                    break;
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Логирование ошибки вместо бросания исключения
            log.error("ffprobe exited with error code: " + exitCode + " for file: " + filePath);
            return false;
        }
        return hasAudio; // true, если найден аудио-трек, иначе false
    }


    private boolean isMediaFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        String mimeType = Files.probeContentType(path);
        if (mimeType == null) {
            return false;
        }
        return mimeType.startsWith("audio") || mimeType.startsWith("video");
    }

    public Mono<String> extractAudio(String sourceFilePath, String taskId, String username) {
        return Mono.when(
                        webSocketService.sendMessage(username, "STATUS: Extracting audio..."),
                        statusService.updateTaskStatus(taskId, "Extracting audio")
                )
                .then(
                        Mono.fromCallable(() -> {
                                    // Form the output file path with .mp3 extension
                                    Path sourcePath = Paths.get(sourceFilePath);
                                    String fileNameWithoutExt = sourcePath.getFileName().toString().replaceFirst("[.][^.]+$", "");
                                    String outputFile = sourcePath.getParent().resolve(fileNameWithoutExt + ".mp3").toString();

                                    ProcessBuilder pbExtractAudio = new ProcessBuilder(
                                            ffmpegPath,
                                            "-fflags", "+genpts",
                                            "-avoid_negative_ts", "make_zero",
                                            "-i", sourceFilePath,
                                            "-vn", // Do not process the video stream
                                            "-acodec", "libmp3lame", // Convert audio to MP3
                                            "-b:a", "128k", // Set bitrate to 128 Kbps
                                            outputFile
                                    ).redirectErrorStream(true);

                                    // Используем общий метод для выполнения процесса
                                    int exitCode = executeFfmpegCommand(pbExtractAudio);

                                    if (exitCode != 0) {
                                        throw new IOException("ffmpeg exited with error code: " + exitCode);
                                    }
                                    log.info("Audio extraction completed for Task ID: {}", taskId);
                                    return outputFile;
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                )
                .onErrorResume(e -> {
                    log.error("Error during audio extraction", e);
                    return Mono.when(
                                    webSocketService.sendErrorMessage(username, "Error extracting audio from the file. Please try again."),
                                    statusService.updateTaskStatus(taskId, "Error")
                            )
                            .then(Mono.error(new RuntimeException("Error during audio extraction", e)));
                });
    }


    public void splitFile(String sourceFilePath, String targetDirectoryPath, int splitLengthInSeconds, String username)
            throws IOException, InterruptedException {

// Определяем расширение исходного файла
        String fileExtension = sourceFilePath.substring(sourceFilePath.lastIndexOf('.'));

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-i", sourceFilePath,
                "-f", "segment",
                "-segment_time", String.valueOf(splitLengthInSeconds),
                "-c", "copy",
                targetDirectoryPath + "/output_part%03d" + fileExtension)
                .redirectErrorStream(true);

        int exitCode = executeFfmpegCommand(pb);

        if (exitCode != 0) {
            throw new IOException("ffmpeg exited with error code " + exitCode);
        }
    }


    private int executeFfmpegCommand(ProcessBuilder processBuilder) throws IOException, InterruptedException {
        Process process = processBuilder.start();

        // Чтение вывода процесса в отдельном потоке
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info(line); // Логируем вывод с указанием Task ID
                }
            } catch (IOException e) {
                log.error("Error reading process output", e);
            }
        }).start();

        // Ожидание завершения процесса
        int exitCode = process.waitFor();
        log.info("Process exited with code: {}", exitCode);
        return exitCode;
    }

}



