package com.oddscanner;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class OddScannerApplication {

    private static final Logger log = LoggerFactory.getLogger(OddScannerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(OddScannerApplication.class, args);
    }
}