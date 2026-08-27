package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.domain.storage.ResourceImageStorage;
import com.gymflow.domain.resource.dto.response.ResourceImageResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 활성 Spring transaction이 없는 순수 Mockito 단위 테스트이므로 afterCompletion에 등록되는
 * S3 cleanup(commit 이후 옛 이미지 삭제 / rollback 이후 새 이미지 보상 삭제)은 이 클래스에서는
 * 실행되지 않는다(TransactionSynchronizationManager.isSynchronizationActive() == false). 실제
 * commit/rollback 시점에 맞춰 cleanup이 수행되는지는 AdminResourceImageServiceTransactionTest에서
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminResourceImageServiceTest {

    private static final Long RESOURCE_ID = 17L;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ResourceImageStorage resourceImageStorage;

    @Mock
    private ResourceCacheRepository resourceCacheRepository;

    @InjectMocks
    private AdminResourceImageService adminResourceImageService;

    private Resource resourceWithId(Long id) {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", id);
        return resource;
    }

    private MultipartFile jpegFile() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", "dummy-image-bytes".getBytes());
    }

    @Test
    @DisplayName("imageKey가 없는 Resource에 신규 이미지를 업로드하면 imageKey가 설정되고 Presigned URL을 반환한다")
    void uploadImage_WithNewImage_ShouldSetImageKeyAndReturnPresignedUrl() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceImageStorage.upload(eq(RESOURCE_ID), any())).thenReturn("resources/17/new-key.jpg");
        when(resourceImageStorage.generateReadUrl("resources/17/new-key.jpg")).thenReturn("https://signed/new-key.jpg");

        // when
        ResourceImageResponse response = adminResourceImageService.uploadImage(RESOURCE_ID, jpegFile());

        // then
        assertThat(response.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(response.imageUrl()).isEqualTo("https://signed/new-key.jpg");
        assertThat(resource.getImageKey()).isEqualTo("resources/17/new-key.jpg");
        verify(resourceCacheRepository).evict(RESOURCE_ID);
    }

    @Test
    @DisplayName("이미 imageKey가 있는 Resource에 새 이미지를 업로드하면 imageKey가 새 값으로 교체된다")
    void uploadImage_WithExistingImage_ShouldReplaceImageKey() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        resource.changeImageKey("resources/17/old-key.jpg");
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceImageStorage.upload(eq(RESOURCE_ID), any())).thenReturn("resources/17/new-key.jpg");
        when(resourceImageStorage.generateReadUrl("resources/17/new-key.jpg")).thenReturn("https://signed/new-key.jpg");

        // when
        ResourceImageResponse response = adminResourceImageService.uploadImage(RESOURCE_ID, jpegFile());

        // then
        assertThat(resource.getImageKey()).isEqualTo("resources/17/new-key.jpg");
        assertThat(response.imageUrl()).isEqualTo("https://signed/new-key.jpg");
        // 활성 transaction이 없으므로 옛 key 삭제(commit 이후 cleanup)는 이 테스트에서 실행되지 않는다.
        verify(resourceImageStorage, never()).delete(any());
    }

    @Test
    @DisplayName("S3 업로드가 실패하면(Fail-Closed) Resource의 imageKey를 변경하지 않는다")
    void uploadImage_WithStorageUploadFailure_ShouldNotChangeImageKey() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        resource.changeImageKey("resources/17/old-key.jpg");
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceImageStorage.upload(eq(RESOURCE_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_IMAGE_UPLOAD_FAILED));

        // when & then
        assertThatThrownBy(() -> adminResourceImageService.uploadImage(RESOURCE_ID, jpegFile()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_IMAGE_UPLOAD_FAILED);
        assertThat(resource.getImageKey()).isEqualTo("resources/17/old-key.jpg");
        verify(resourceCacheRepository, never()).evict(any());
    }

    @Test
    @DisplayName("존재하지 않는 Resource에 이미지를 업로드하면 RESOURCE_NOT_FOUND 예외가 발생하고 업로드를 시도하지 않는다")
    void uploadImage_WithNonExistentResource_ShouldThrowResourceNotFound() {
        // given
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminResourceImageService.uploadImage(RESOURCE_ID, jpegFile()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        verify(resourceImageStorage, never()).upload(anyLong(), any());
    }

    @Test
    @DisplayName("빈 파일을 업로드하면 INVALID_IMAGE_FILE 예외가 발생한다")
    void uploadImage_WithEmptyFile_ShouldThrowInvalidImageFile() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        MultipartFile emptyFile = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[0]);

        // when & then
        assertThatThrownBy(() -> adminResourceImageService.uploadImage(RESOURCE_ID, emptyFile))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_FILE);
        verify(resourceImageStorage, never()).upload(anyLong(), any());
    }

    @Test
    @DisplayName("5MB를 초과하는 파일을 업로드하면 IMAGE_FILE_TOO_LARGE 예외가 발생한다")
    void uploadImage_WithFileOverFiveMegabytes_ShouldThrowImageFileTooLarge() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        MultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", oversized);

        // when & then
        assertThatThrownBy(() -> adminResourceImageService.uploadImage(RESOURCE_ID, file))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_FILE_TOO_LARGE);
        verify(resourceImageStorage, never()).upload(anyLong(), any());
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type(image/gif)을 업로드하면 UNSUPPORTED_IMAGE_TYPE 예외가 발생한다")
    void uploadImage_WithUnsupportedContentType_ShouldThrowUnsupportedImageType() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        MultipartFile file = new MockMultipartFile("file", "photo.gif", "image/gif", "dummy".getBytes());

        // when & then
        assertThatThrownBy(() -> adminResourceImageService.uploadImage(RESOURCE_ID, file))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        verify(resourceImageStorage, never()).upload(anyLong(), any());
    }

    @Test
    @DisplayName("Content-Type이 없는 파일을 업로드하면 UNSUPPORTED_IMAGE_TYPE 예외가 발생한다")
    void uploadImage_WithNullContentType_ShouldThrowUnsupportedImageType() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        MultipartFile file = new MockMultipartFile("file", "photo", null, "dummy".getBytes());

        // when & then
        assertThatThrownBy(() -> adminResourceImageService.uploadImage(RESOURCE_ID, file))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        verify(resourceImageStorage, never()).upload(anyLong(), any());
    }

    @Test
    @DisplayName("imageKey가 없는 Resource를 삭제 요청하면 아무 것도 하지 않고 성공한다 (멱등)")
    void deleteImage_WithoutExistingImage_ShouldSucceedIdempotently() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        adminResourceImageService.deleteImage(RESOURCE_ID);

        // then
        verify(resourceImageStorage, never()).delete(any());
        verify(resourceCacheRepository, never()).evict(any());
    }

    @Test
    @DisplayName("imageKey가 있는 Resource를 삭제하면 imageKey가 null이 되고 Cache를 evict한다")
    void deleteImage_WithExistingImage_ShouldClearImageKeyAndEvictCache() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID);
        resource.changeImageKey("resources/17/old-key.jpg");
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        adminResourceImageService.deleteImage(RESOURCE_ID);

        // then
        assertThat(resource.getImageKey()).isNull();
        verify(resourceCacheRepository).evict(RESOURCE_ID);
        // 활성 transaction이 없으므로 실제 S3 delete(commit 이후 cleanup)는 이 테스트에서 실행되지 않는다.
        verify(resourceImageStorage, never()).delete(any());
    }

    @Test
    @DisplayName("존재하지 않는 Resource를 삭제 요청하면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void deleteImage_WithNonExistentResource_ShouldThrowResourceNotFound() {
        // given
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminResourceImageService.deleteImage(RESOURCE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }
}
