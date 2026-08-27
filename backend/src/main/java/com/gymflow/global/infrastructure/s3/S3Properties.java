package com.gymflow.global.infrastructure.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws")
public record S3Properties(String region, S3 s3) {

    public record S3(String resourceImageBucket) {
    }
}
