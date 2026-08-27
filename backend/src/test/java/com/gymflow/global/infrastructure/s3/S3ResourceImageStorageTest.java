package com.gymflow.global.infrastructure.s3;

import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ResourceImageStorageTest {

    private static final Long RESOURCE_ID = 17L;
    private static final String BUCKET = "gymflow-resource-images";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private final S3Properties s3Properties = new S3Properties("ap-northeast-2", new S3Properties.S3(BUCKET));

    private S3ResourceImageStorage storageWithProperties() {
        return new S3ResourceImageStorage(s3Client, s3Presigner, s3Properties);
    }

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", "dummy".getBytes());
    }

    @Test
    @DisplayName("upload()는 resources/{resourceId}/{UUID}.{extension} 형식의 key로 PutObject를 요청하고 그 key를 반환한다")
    void upload_WithJpeg_ShouldUseUuidBasedKeyUnderResourcePrefix() {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // when
        String key = target.upload(RESOURCE_ID, jpegFile());

        // then
        assertThat(key).matches(Pattern.compile(
                "resources/" + RESOURCE_ID + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"));
        assertThat(UUID.fromString(key.split("/")[2].replace(".jpg", ""))).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
            "image/jpeg,jpg",
            "image/png,png",
            "image/webp,webp"
    })
    @DisplayName("upload()는 Content-Type에 따라 올바른 확장자를 key에 사용한다")
    void upload_WithEachSupportedContentType_ShouldMapToCorrectExtension(String contentType, String extension) {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        MockMultipartFile file = new MockMultipartFile("file", "photo", contentType, "dummy".getBytes());
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // when
        String key = target.upload(RESOURCE_ID, file);

        // then
        assertThat(key).endsWith("." + extension);
    }

    @Test
    @DisplayName("upload()는 bucket/key/Content-Type을 담아 PutObjectRequest를 생성한다")
    void upload_ShouldBuildPutObjectRequestWithBucketKeyAndContentType() {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // when
        String key = target.upload(RESOURCE_ID, jpegFile());

        // then
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo(key);
        assertThat(request.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("upload() 중 S3 SDK 예외가 발생하면 RESOURCE_IMAGE_UPLOAD_FAILED로 변환해 던진다 (SDK 예외를 그대로 노출하지 않는다)")
    void upload_WithSdkException_ShouldWrapIntoBusinessException() {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.create("boom", null));

        // when & then
        assertThatThrownBy(() -> target.upload(RESOURCE_ID, jpegFile()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_IMAGE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("delete()는 bucket/key로 DeleteObjectRequest를 요청한다")
    void delete_ShouldSendDeleteObjectRequestWithBucketAndKey() {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        String key = "resources/17/sample.jpg";

        // when
        target.delete(key);

        // then
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(key);
    }

    @Test
    @DisplayName("delete() 중 S3 SDK 예외가 발생하면 RESOURCE_IMAGE_DELETE_FAILED로 변환해 던진다")
    void delete_WithSdkException_ShouldWrapIntoBusinessException() {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        doThrow(SdkException.create("boom", null)).when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        // when & then
        assertThatThrownBy(() -> target.delete("resources/17/sample.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_IMAGE_DELETE_FAILED);
    }

    @Test
    @DisplayName("generateReadUrl()은 1시간(Duration.ofHours(1)) 유효한 Presigned GET URL을 생성한다")
    void generateReadUrl_ShouldPresignWithOneHourExpiration() throws MalformedURLException {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        String key = "resources/17/sample.jpg";
        PresignedGetObjectRequest presigned = mockPresignedRequest("https://example.com/signed");
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        // when
        String url = target.generateReadUrl(key);

        // then
        assertThat(url).isEqualTo("https://example.com/signed");
        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(captor.capture());
        GetObjectPresignRequest request = captor.getValue();
        assertThat(request.signatureDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(request.getObjectRequest().bucket()).isEqualTo(BUCKET);
        assertThat(request.getObjectRequest().key()).isEqualTo(key);
    }

    @Test
    @DisplayName("generateReadUrl() 중 S3 SDK 예외가 발생하면 RESOURCE_IMAGE_URL_GENERATION_FAILED로 변환해 던진다")
    void generateReadUrl_WithSdkException_ShouldWrapIntoBusinessException() {
        // given
        S3ResourceImageStorage target = storageWithProperties();
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(SdkException.create("boom", null));

        // when & then
        assertThatThrownBy(() -> target.generateReadUrl("resources/17/sample.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_IMAGE_URL_GENERATION_FAILED);
    }

    private PresignedGetObjectRequest mockPresignedRequest(String url) throws MalformedURLException {
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create(url).toURL());
        return presigned;
    }
}
