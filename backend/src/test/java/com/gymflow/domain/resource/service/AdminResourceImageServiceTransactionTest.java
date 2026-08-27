package com.gymflow.domain.resource.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.domain.storage.ResourceImageStorage;
import com.gymflow.domain.resource.dto.response.ResourceImageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminResourceImageService가 등록하는 TransactionSynchronization이 실제 Spring transaction의
 * afterCompletion(commit/rollback)에 맞춰 S3 cleanup을 수행하는지, 실제 Testcontainers MySQL과
 * 진짜 transaction 경계로 검증한다. ResourceImageStorage는 실제 AWS S3를 호출하지 않도록
 * MockitoBean으로 대체한다.
 *
 * 각 테스트는 Service 호출을 외부 TransactionTemplate으로 감싸 "commit되기 전에는 cleanup이
 * 실행되지 않고, commit 이후에만 실행된다"는 순서를 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminResourceImageServiceTransactionTest {

    @Autowired
    private AdminResourceImageService adminResourceImageService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ResourceImageStorage resourceImageStorage;

    private Long persistResourceWithImageKey(String imageKey) {
        Resource resource = Resource.builder()
                .name("Transaction Test Resource " + System.nanoTime())
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        if (imageKey != null) {
            resource.changeImageKey(imageKey);
        }
        return resourceRepository.save(resource).getId();
    }

    private MultipartFile jpegFile() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", "dummy-image-bytes".getBytes());
    }

    @Test
    @DisplayName("이미지 교체 후 outer transaction이 commit되기 전에는 옛 이미지를 지우지 않고, commit 이후에만 지운다")
    void uploadImage_OnCommit_ShouldDeleteOldImageOnlyAfterCommit() {
        // given
        Long resourceId = persistResourceWithImageKey("resources/old/before-commit.jpg");
        reset(resourceImageStorage);
        when(resourceImageStorage.upload(any(), any())).thenReturn("resources/new/after-commit.jpg");
        when(resourceImageStorage.generateReadUrl("resources/new/after-commit.jpg")).thenReturn("https://signed/new");

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);

        // when
        outerTemplate.execute(status -> {
            ResourceImageResponse response = adminResourceImageService.uploadImage(resourceId, jpegFile());
            assertThat(response.imageUrl()).isEqualTo("https://signed/new");
            // then: commit 이전에는 아직 옛 이미지를 지우면 안 된다
            verify(resourceImageStorage, never()).delete(any());
            return null;
        });

        // then: outer transaction이 commit된 이후에는 옛 이미지가 삭제된다
        verify(resourceImageStorage).delete("resources/old/before-commit.jpg");
    }

    @Test
    @DisplayName("이미지 교체 도중 outer transaction이 rollback되면, 새로 올린 이미지를 보상 삭제하고 옛 이미지는 지우지 않는다")
    void uploadImage_OnRollback_ShouldCompensateNewImageAndKeepOldImage() {
        // given
        Long resourceId = persistResourceWithImageKey("resources/old/keep-me.jpg");
        reset(resourceImageStorage);
        when(resourceImageStorage.upload(any(), any())).thenReturn("resources/new/compensate-me.jpg");
        when(resourceImageStorage.generateReadUrl("resources/new/compensate-me.jpg")).thenReturn("https://signed/new");

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);

        // when
        outerTemplate.execute(status -> {
            adminResourceImageService.uploadImage(resourceId, jpegFile());
            status.setRollbackOnly();
            return null;
        });

        // then: rollback되었으므로 새로 올린 Object는 보상 삭제되고, 옛 이미지는 그대로 유지된다
        verify(resourceImageStorage).delete("resources/new/compensate-me.jpg");
        verify(resourceImageStorage, never()).delete("resources/old/keep-me.jpg");

        Resource reloaded = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(reloaded.getImageKey()).isEqualTo("resources/old/keep-me.jpg");
    }

    @Test
    @DisplayName("이미지 삭제 후 outer transaction이 commit되기 전에는 S3에서 지우지 않고, commit 이후에만 지운다")
    void deleteImage_OnCommit_ShouldDeleteFromS3OnlyAfterCommit() {
        // given
        Long resourceId = persistResourceWithImageKey("resources/old/delete-after-commit.jpg");
        reset(resourceImageStorage);

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);

        // when
        outerTemplate.execute(status -> {
            adminResourceImageService.deleteImage(resourceId);
            verify(resourceImageStorage, never()).delete(any());
            return null;
        });

        // then
        verify(resourceImageStorage).delete("resources/old/delete-after-commit.jpg");
        Resource reloaded = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(reloaded.getImageKey()).isNull();
    }

    @Test
    @DisplayName("이미지 삭제 도중 outer transaction이 rollback되면 S3 Object를 지우지 않고 DB의 imageKey도 그대로 유지된다")
    void deleteImage_OnRollback_ShouldNotDeleteFromS3AndKeepImageKey() {
        // given
        Long resourceId = persistResourceWithImageKey("resources/old/keep-on-rollback.jpg");
        reset(resourceImageStorage);

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);

        // when
        outerTemplate.execute(status -> {
            adminResourceImageService.deleteImage(resourceId);
            status.setRollbackOnly();
            return null;
        });

        // then
        verify(resourceImageStorage, never()).delete(any());
        Resource reloaded = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(reloaded.getImageKey()).isEqualTo("resources/old/keep-on-rollback.jpg");
    }
}
