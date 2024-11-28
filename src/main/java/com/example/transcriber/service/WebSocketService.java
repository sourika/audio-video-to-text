package com.example.transcriber.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;


@Service
@Data
@Slf4j
public class WebSocketService {

    // Хранилище сессий WebSocket
    private final ConcurrentHashMap<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();


    public void addSession(String username, WebSocketSession session) {
        sessionMap.put(username, session);
    }

    public void removeSession(String username) {
        sessionMap.remove(username);
    }


    // Отправляем сообщение конкретному пользователю
    public Mono<Void> sendMessage(String username, String message) {
        WebSocketSession session = sessionMap.get(username);
        log.info("Sending message to user {}: {}", username, message);
        if (session != null && session.isOpen()) {
            return session.send(Mono.just(session.textMessage(message)))
                    .then()
                    .doOnSuccess(unused -> log.info("Message sent successfully to user {}", username))
                    .doOnError(e -> log.error("Failed to send message to user {}", username, e));
        } else {
            log.error("No active WebSocket session for user: " + username);
            return Mono.empty();
        }
    }

    // Отправляем ошибку конкретному пользователю
    public Mono<Void> sendErrorMessage(String username, String errorMsg) {
        WebSocketSession session = sessionMap.get(username);
        log.info("Sending error message to user {}: {}", username, errorMsg);
        if (session != null && session.isOpen()) {
            return session.send(Mono.just(session.textMessage("ERROR: " + errorMsg)))
                    .then() // Преобразуем Flux<Void> в Mono<Void>
                    .doOnSuccess(unused -> log.info("Error message sent successfully to user {}", username))
                    .doOnError(e -> log.error("Failed to send error message to user {}", username, e));
        } else {
            log.error("No active WebSocket session for user: " + username);
            return Mono.empty();
        }
    }
}

