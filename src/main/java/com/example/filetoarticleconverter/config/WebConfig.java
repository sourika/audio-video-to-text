package com.example.filetoarticleconverter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;



@Configuration
@ComponentScan("com.example.filetoarticleconverter")
public class WebConfig {


    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create().followRedirect(true);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
