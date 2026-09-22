package com.oak.dms.ocrworker;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class OcrWorkerRestTemplateConfig {

    private final OcrWorkerProperties properties;

    @Bean
    public RestTemplate ocrWorkerRestTemplate(RestTemplateBuilder builder) {
        // Explicit SimpleClientHttpRequestFactory (java.net.HttpURLConnection,
        // HTTP/1.1 only) rather than this builder's own default -- Spring
        // Boot's default ClientHttpRequestFactoryBuilder.detect() prefers the
        // JDK's java.net.http.HttpClient when available, and that client
        // defaults to attempting an HTTP/2 cleartext (h2c) upgrade on every
        // plain-http:// request. ocr-worker's uvicorn only speaks HTTP/1.1
        // and rejects that upgrade attempt outright (its own h11 layer logs
        // "Unsupported upgrade request."/"Invalid HTTP request received.",
        // returned to this client as a spurious 400) -- verified live: every
        // call failed this way until pinned to HTTP/1.1 here, even though
        // ocr-worker's pipeline still ran to completion in the background
        // (a plain HTTP/1.1 curl to the same endpoint succeeded immediately).
        return builder
                .requestFactory(SimpleClientHttpRequestFactory::new)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
    }
}
