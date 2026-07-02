package com.dating.match;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * match-service entry point.
 * Card feed, swipe, matching, quota, likes/visits microservice.
 *
 * @see docs/match-service-prd-tech.md
 */
@SpringBootApplication
public class MatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchApplication.class, args);
    }
}
