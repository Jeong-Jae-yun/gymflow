package com.gymflow.domain.resource.domain.storage;

import org.springframework.web.multipart.MultipartFile;

public interface ResourceImageStorage {
    String upload(Long resourceId, MultipartFile file);
    void delete(String imageKey);
    String generateReadUrl(String imageKey);
}
