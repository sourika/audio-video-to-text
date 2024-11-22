package com.example.filetoarticleconverter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Data
@AllArgsConstructor
@Slf4j
public class IndexedText {

    private final int index;
    private final String text;

    public static int getIndexFromFileName(Path path) {
        String fileName = path.getFileName().toString();
        String indexPart = fileName.replaceAll("\\D", ""); // Извлечение чисел из имени файла

        if (indexPart.isEmpty()) {
            throw new IllegalArgumentException("Filename does not contain any digits: " + fileName);
        }

        try {
            return Integer.parseInt(indexPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failed to parse index from filename: " + fileName, e);
        }
    }
}
