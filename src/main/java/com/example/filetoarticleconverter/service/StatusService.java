package com.example.filetoarticleconverter.service;

import com.example.filetoarticleconverter.dto.TaskData;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

@Service
@Data
@Slf4j
public class StatusService {
    private final ConcurrentHashMap<String, TaskData> tasks = new ConcurrentHashMap<>();

    public Mono<Void> updateTaskStatus(String taskId, String status) {
        return Mono.fromRunnable(() -> {
            TaskData taskData = tasks.getOrDefault(taskId, new TaskData(status, null));
            taskData.setStatus(status);
            tasks.put(taskId, taskData);
            log.info("Update status for Task ID {}: {}", taskId, taskData.getStatus());
        });
    }

    public Mono<Void> setRedirectUrl(String taskId, String redirectUrl) {
        return Mono.fromRunnable(() -> {
            TaskData taskData = tasks.getOrDefault(taskId, new TaskData("Unknown", redirectUrl));
            taskData.setRedirectUrl(redirectUrl);
            tasks.put(taskId, taskData);
        });
    }

    public Mono<TaskData> getTaskData(String taskId) {
        TaskData taskData = tasks.getOrDefault(taskId, new TaskData("Unknown", null));
        return Mono.just(taskData);
    }
}