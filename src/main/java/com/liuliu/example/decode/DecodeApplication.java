package com.liuliu.example.decode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DecodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DecodeApplication.class, args);
    }
}