package com.yizhaoqi.intellidoc.service;

import com.yizhaoqi.intellidoc.client.EmbeddingClient;
import com.yizhaoqi.intellidoc.entity.EsDocument;
import com.yizhaoqi.intellidoc.model.DocumentVector;
import com.yizhaoqi.intellidoc.repository.DocumentVectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorizationIsolationTest {
    @Mock DocumentVectorRepository repository;
    @Mock EmbeddingClient client;
    @Mock ElasticsearchService search;
    @InjectMocks VectorizationService service;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onlyUploaderChunksAreEmbeddedAndIndexed() {
        DocumentVector chunk = new DocumentVector(); chunk.setFileMd5("abc"); chunk.setUserId("1");
        chunk.setChunkId(0); chunk.setTextContent("owner content"); chunk.setPageNumber(3);
        when(repository.findByFileMd5AndUserIdOrderByChunkIdAsc("abc", "1")).thenReturn(List.of(chunk));
        when(client.embedWithUsage(List.of("owner content"), "1", EmbeddingClient.UsageType.UPLOAD))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(List.of(new float[]{1f}), 5, "model"));
        service.vectorizeWithUsage("abc", "1", "TEAM_1", false, "1");
        ArgumentCaptor<List<EsDocument>> docs = ArgumentCaptor.forClass((Class) List.class);
        verify(search).bulkIndex(docs.capture());
        assertEquals(1, docs.getValue().size());
        assertEquals("1", docs.getValue().get(0).getUserId());
        assertEquals(3, docs.getValue().get(0).getPageNumber());
        verify(repository, never()).findByFileMd5OrderByChunkIdAsc(anyString());
    }
}
