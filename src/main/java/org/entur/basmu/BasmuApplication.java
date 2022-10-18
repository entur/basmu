package org.entur.basmu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.task.configuration.EnableTask;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableTask
public class BasmuApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasmuApplication.class, args);
    }

    @Bean
    public BasmuTaskListener taskExecutionListener() {
        return new BasmuTaskListener();
    }
}
