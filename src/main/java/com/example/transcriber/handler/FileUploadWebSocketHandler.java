package com.example.transcriber.handler;


import com.example.transcriber.service.WebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;


@Component
@Slf4j
public class FileUploadWebSocketHandler implements WebSocketHandler {
    private final WebSocketService webSocketService;


    @Autowired
    public FileUploadWebSocketHandler(WebSocketService webSocketService) {
        this.webSocketService = webSocketService;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String query = session.getHandshakeInfo().getUri().getQuery();
        String username = query != null && query.startsWith("username=") ? query.substring("username=".length()) : null;
        log.info("User connected: " + username);

        if (username == null || username.isEmpty()) {
            log.error("User information is not available in the WebSocket session.");
            return session.close(CloseStatus.BAD_DATA.withReason("User information is missing"));
        }

        // Сохраняем сессию
        webSocketService.addSession(username, session);

        return session.receive()
                .doFinally(signalType -> {
                    // Удаляем сессию при завершении
                    webSocketService.removeSession(username);
                    log.info("User disconnected: " + username);
                })
                .then(); // Завершаем всю цепочку
    }
}
