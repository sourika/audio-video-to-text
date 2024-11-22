package com.example.filetoarticleconverter.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status;

        if (ex.getStatusCode() instanceof HttpStatus) {
            // Если getStatusCode() возвращает HttpStatus, используем его напрямую
            status = (HttpStatus) ex.getStatusCode();
        } else {
            // Иначе преобразуем статусный код в HttpStatus
            status = HttpStatus.resolve(ex.getStatusCode().value());
            if (status == null) {
                // Если статус не распознан, используем INTERNAL_SERVER_ERROR по умолчанию
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
        }

        return ResponseEntity.status(status)
                .body(ex.getReason());
    }
}