package com.docuware;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DocuWareApplication {
    public static void main(String[] args) { SpringApplication.run(DocuWareApplication.class, args); }
}
