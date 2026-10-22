package com.bharad.recoverymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RecoveryMeshApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecoveryMeshApplication.class, args);
    }
}
