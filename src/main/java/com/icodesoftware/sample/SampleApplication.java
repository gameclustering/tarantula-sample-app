package com.icodesoftware.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SampleApplication {
    public static void main(String[] args) {
        VaultLoader.load();
        SpringApplication.run(SampleApplication.class, args);
    }
}
