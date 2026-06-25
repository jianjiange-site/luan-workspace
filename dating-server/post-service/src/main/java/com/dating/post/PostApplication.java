package com.dating.post;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * post-service entry point.
 * UGC content microservice: posts, likes, comments, recommendation feed.
 *
 * @see docs/post-service-design.md
 */
@SpringBootApplication
public class PostApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostApplication.class, args);
    }
}
