package com.yizhaoqi.intellidoc.service;

import com.yizhaoqi.intellidoc.model.*;
import com.yizhaoqi.intellidoc.repository.*;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIsolationTest {
    @Mock FileUploadRepository files;
    @Mock DocumentVectorRepository vectors;
    @Mock ChunkInfoRepository chunks;
    @Mock ElasticsearchService search;
    @Mock MinioClient minio;
    @Mock UploadService upload;
    @Mock StringRedisTemplate redis;
    @Mock ParseService parser;
    @Mock VectorizationService embedding;
    @InjectMocks DocumentService service;

    @BeforeEach
    void setup() { ReflectionTestUtils.setField(service, "bucketName", "uploads"); }

    private FileUpload file(String user) {
        FileUpload file = new FileUpload(); file.setFileMd5("abc"); file.setUserId(user);
        file.setFileName("same.pdf"); file.setOrgTag("TEAM_" + user);
        return file;
    }

    @Test
    void deletingOneReferencePreservesOtherUsersSharedContent() {
        when(files.findAllByFileMd5ForUpdate("abc")).thenReturn(List.of(file("1"), file("2")));
        service.deleteDocument("abc", "1");
        verify(files).deleteByFileMd5AndUserId("abc", "1");
        verify(vectors).deleteByFileMd5AndUserId("abc", "1");
        verify(search).deleteByFileMd5AndUserId("abc", "1");
        verify(upload).deleteFileMark("abc", "1");
        verify(files, never()).deleteByFileMd5(anyString());
        verify(vectors, never()).deleteByFileMd5(anyString());
        verify(search, never()).deleteByFileMd5(anyString());
        verifyNoInteractions(minio, chunks, redis);
    }

    @Test
    void lastReferenceReleasesSharedContentInConfiguredBucket() throws Exception {
        when(files.findAllByFileMd5ForUpdate("abc")).thenReturn(List.of(file("1")));
        ReflectionTestUtils.setField(service, "bucketName", "custom-bucket");
        service.deleteDocument("abc", "1");
        ArgumentCaptor<RemoveObjectArgs> args = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minio).removeObject(args.capture());
        assertEquals("custom-bucket", args.getValue().bucket());
        assertEquals("merged/abc", args.getValue().object());
        verify(chunks).deleteByFileMd5("abc");
    }

    @Test
    void foreignDocumentCannotBeDeleted() {
        when(files.findAllByFileMd5ForUpdate("abc")).thenReturn(List.of(file("2")));
        assertThrows(IllegalArgumentException.class, () -> service.deleteDocument("abc", "1"));
        verify(files, never()).deleteByFileMd5AndUserId(anyString(), anyString());
        verifyNoInteractions(search, vectors, minio, chunks, upload);
    }

    @Test
    void failedSearchCleanupRetainsReferenceForRetry() {
        when(files.findAllByFileMd5ForUpdate("abc")).thenReturn(List.of(file("1"), file("2")));
        doThrow(new RuntimeException("ES unavailable")).when(search).deleteByFileMd5AndUserId("abc", "1");
        assertThrows(RuntimeException.class, () -> service.deleteDocument("abc", "1"));
        verify(files, never()).deleteByFileMd5AndUserId(anyString(), anyString());
        verifyNoInteractions(vectors, minio, chunks, upload);
    }

    @Test
    void reindexOnlyClearsSelectedUploadersData() throws Exception {
        FileUpload selected = file("1");
        when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("abc", "1")).thenReturn(Optional.of(selected));
        when(upload.getMergedFileStream("abc")).thenReturn(mock(io.minio.GetObjectResponse.class));
        var result = new VectorizationService.VectorizationUsageResult(12, 2, "model");
        when(embedding.vectorizeWithUsage("abc", "1", "TEAM_1", false, "admin")).thenReturn(result);
        assertEquals(result, service.reindexDocument("abc", "1", "admin"));
        verify(search).deleteByFileMd5AndUserId("abc", "1");
        verify(vectors).deleteByFileMd5AndUserId("abc", "1");
        verify(search, never()).deleteByFileMd5(anyString());
        verify(vectors, never()).deleteByFileMd5(anyString());
        verify(files, never()).findFirstByFileMd5OrderByCreatedAtDesc(anyString());
    }

    @Test
    void taskCompletionUpdatesItsUploaderRatherThanLatestMd5Record() {
        FileUpload selected = file("1");
        when(files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("abc", "1")).thenReturn(Optional.of(selected));
        service.markVectorizationCompleted("abc", "1", new VectorizationService.VectorizationUsageResult(12, 2, "model"));
        verify(files).save(selected);
        assertEquals(FileUpload.VECTORIZATION_STATUS_COMPLETED, selected.getVectorizationStatus());
        verify(files, never()).findFirstByFileMd5OrderByCreatedAtDesc(anyString());
    }
}
