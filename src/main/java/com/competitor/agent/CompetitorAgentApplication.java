package com.competitor.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.competitor.agent.mapper")
public class CompetitorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompetitorAgentApplication.class, args);
    }
}
