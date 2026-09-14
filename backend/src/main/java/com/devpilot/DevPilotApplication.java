package com.devpilot;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication @EnableScheduling public class DevPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(DevPilotApplication.class,args);
    }
}
