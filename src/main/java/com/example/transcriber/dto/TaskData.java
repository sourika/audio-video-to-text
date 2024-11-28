package com.example.transcriber.dto;

import lombok.Data;

@Data
public class TaskData {

    private String status;
    private String redirectUrl;

    public TaskData(String status, String redirectUrl) {
        this.status = status;
        this.redirectUrl = redirectUrl;
    }
}
