package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.domain.storage.ResourceImageStorage;
import com.gymflow.domain.resource.dto.response.ResourceImageResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.function.IntConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminResourceImageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    private final ResourceRepository resourceRepository;
    private final ResourceImageStorage resourceImageStorage;
    private final ResourceCacheRepository resourceCacheRepository;

    @Transactional
    public ResourceImageResponse uploadImage(Long resourceId, MultipartFile file) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        validateImageFile(file);

        String oldImageKey = resource.getImageKey();
        String newImageKey = resourceImageStorage.upload(resourceId, file);
        resource.changeImageKey(newImageKey);

        evictCache(resourceId);
        registerReplaceCompensation(oldImageKey, newImageKey);

        String imageUrl = resourceImageStorage.generateReadUrl(newImageKey);
        return new ResourceImageResponse(resourceId, imageUrl);
    }

    @Transactional
    public void deleteImage(Long resourceId) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        String oldImageKey = resource.getImageKey();
        if (oldImageKey == null) {
            return;
        }

        resource.removeImageKey();
        evictCache(resourceId);
        registerDeleteCleanup(oldImageKey);
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_TOO_LARGE);
        }
    }

    private void registerReplaceCompensation(String oldImageKey, String newImageKey) {
        registerAfterCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                if (oldImageKey != null) {
                    deleteQuietly(oldImageKey, "기존 이미지 정리(commit 이후)");
                }
            } else {
                deleteQuietly(newImageKey, "신규 이미지 보상 삭제(rollback 이후)");
            }
        });
    }

    private void registerDeleteCleanup(String oldImageKey) {
        registerAfterCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                deleteQuietly(oldImageKey, "이미지 삭제(commit 이후)");
            }
        });
    }

    private void registerAfterCompletion(IntConsumer onCompletion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                onCompletion.accept(status);
            }
        });
    }

    private void deleteQuietly(String imageKey, String context) {
        try {
            resourceImageStorage.delete(imageKey);
        } catch (RuntimeException e) {
            log.warn("S3 Object 정리에 실패했습니다 ({}). key={}", context, imageKey, e);
        }
    }

    private void evictCache(Long resourceId) {
        try {
            resourceCacheRepository.evict(resourceId);
        } catch (RuntimeException e) {
            log.warn("Resource Cache Evict에 실패했습니다. resourceId={}", resourceId, e);
        }
    }
}
