package com.gymflow.global.infrastructure.s3;

import com.gymflow.domain.resource.domain.storage.ResourceImageStorage;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3ResourceImageStorage implements ResourceImageStorage {

    private static final Duration PRESIGNED_URL_EXPIRATION = Duration.ofHours(1);
    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    @Override
    public String upload(Long resourceId, MultipartFile file) {
        String key = generateKey(resourceId, file.getContentType());
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return key;
        } catch (IOException | SdkException e) {
            log.error("Resource 이미지 업로드에 실패했습니다. resourceId={}, key={}", resourceId, key, e);
            throw new BusinessException(ErrorCode.RESOURCE_IMAGE_UPLOAD_FAILED);
        }
    }

    @Override
    public void delete(String imageKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket())
                    .key(imageKey)
                    .build());
        } catch (SdkException e) {
            log.error("Resource 이미지 삭제에 실패했습니다. key={}", imageKey, e);
            throw new BusinessException(ErrorCode.RESOURCE_IMAGE_DELETE_FAILED);
        }
    }

    @Override
    public String generateReadUrl(String imageKey) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(imageKey)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGNED_URL_EXPIRATION)
                    .getObjectRequest(getObjectRequest)
                    .build();
            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (SdkException e) {
            log.error("Resource 이미지 Presigned URL 생성에 실패했습니다. key={}", imageKey, e);
            throw new BusinessException(ErrorCode.RESOURCE_IMAGE_URL_GENERATION_FAILED);
        }
    }

    private String bucket() {
        return s3Properties.s3().resourceImageBucket();
    }

    private String generateKey(Long resourceId, String contentType) {
        String extension = EXTENSION_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        return "resources/%d/%s.%s".formatted(resourceId, UUID.randomUUID(), extension);
    }
}
