package org.entur.basmu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.task.configuration.EnableTask;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableTask
public class BalhutApplication {

    public static void main(String[] args) {
        SpringApplication.run(BalhutApplication.class, args);
    }

    @Bean
    public BalhutTaskListener taskExecutionListener() {
        return new BalhutTaskListener();
    }
}
