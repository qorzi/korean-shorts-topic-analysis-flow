package com.analysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VideoProcessingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoProcessingServiceApplication.class, args);
    }
}
