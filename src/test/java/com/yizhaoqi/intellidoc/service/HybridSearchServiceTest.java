package com.yizhaoqi.intellidoc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.intellidoc.client.EmbeddingClient;
import com.yizhaoqi.intellidoc.config.HybridSearchProperties;
import com.yizhaoqi.intellidoc.entity.EsDocument;
import com.yizhaoqi.intellidoc.exception.CustomException;
import com.yizhaoqi.intellidoc.model.User;
import com.yizhaoqi.intellidoc.model.FileUpload;
import com.yizhaoqi.intellidoc.repository.FileUploadRepository;
import com.yizhaoqi.intellidoc.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class HybridSearchServiceTest {
    private HybridSearchService service;
    private ElasticsearchClient es;
    private co.elastic.clients.transport.ElasticsearchTransport transport;
    private EmbeddingClient embedding;
    private final List<SearchRequest> requests = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        service = new HybridSearchService();
        transport = mock(co.elastic.clients.transport.ElasticsearchTransport.class);
        es = new ElasticsearchClient(transport); embedding = mock(EmbeddingClient.class);
        UserRepository users = mock(UserRepository.class); OrgTagCacheService orgs = mock(OrgTagCacheService.class);
        User user = new User(); user.setId(1L); user.setUsername("alice");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(orgs.getUserEffectiveOrgTags("alice")).thenReturn(List.of("TEAM_A", "PRIVATE_alice", "PRIVATE_bob"));
        when(embedding.embed(anyList(), eq("1"), eq(EmbeddingClient.UsageType.QUERY)))
                .thenReturn(List.of(new float[]{1f}));
        var properties = new HybridSearchProperties();
        ReflectionTestUtils.setField(service, "esClient", es);
        ReflectionTestUtils.setField(service, "embeddingClient", embedding);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "orgTagCacheService", orgs);
        ReflectionTestUtils.setField(service, "searchProperties", properties);
        ReflectionTestUtils.setField(service, "ranker", new HybridSearchRanker(properties));
        SearchResponse<EsDocument> response = SearchResponse.of(s -> s.took(1).timedOut(false)
                .shards(sh -> sh.total(1).successful(1).failed(0)).hits(h -> h.hits(List.of())));
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(0, SearchRequest.class));
            return response;
        }).when(transport).performRequest(any(SearchRequest.class), any(), any());
    }

    @Test
    void bothRecallChannelsKeepUserAndOrganizationPermissionFilters() {
        assertTrue(service.searchWithPermission("question", "1", 5).isEmpty());
        assertEquals(2, requests.size());
        for (var request : requests) {
            String json = request.toString();
            assertTrue(json.contains("userId")); assertTrue(json.contains("TEAM_A")); assertTrue(json.contains("public"));
            assertFalse(json.contains("PRIVATE_alice")); assertFalse(json.contains("PRIVATE_bob"));
        }
    }

    @Test
    void defaultRrfPromotesSharedHitsAndPreservesReferenceMetadata() throws Exception {
        EsDocument shared = new EsDocument("shared", "file-md5", 7, "matched evidence",
                3, "page anchor", null, null, "1", "TEAM_A", false);
        EsDocument vectorFirst = new EsDocument();
        EsDocument textFirst = new EsDocument();
        var knn = List.of(
                Hit.of(h -> h.index("knowledge_base").id("vector").score(.99).source(vectorFirst)),
                Hit.of(h -> h.index("knowledge_base").id("shared").score(.98).source(shared)));
        var bm25 = List.of(
                Hit.of(h -> h.index("knowledge_base").id("text").score(1000d).source(textFirst)),
                Hit.of(h -> h.index("knowledge_base").id("shared").score(1d).source(shared)));
        doAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0, SearchRequest.class);
            requests.add(request);
            return SearchResponse.of(s -> s.took(1).timedOut(false)
                    .shards(sh -> sh.total(1).successful(1).failed(0))
                    .hits(h -> h.hits(request.knn().isEmpty() ? bm25 : knn)));
        }).when(transport).performRequest(any(SearchRequest.class), any(), any());
        var files = mock(FileUploadRepository.class);
        FileUpload upload = new FileUpload();
        upload.setFileMd5("file-md5"); upload.setFileName("evidence.pdf");
        when(files.findByFileMd5In(anyList())).thenReturn(List.of(upload));
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);

        var results = service.searchWithPermission("question", "1", 1);

        assertEquals(2, requests.size());
        assertEquals(1, results.size());
        var result = results.get(0);
        assertEquals("file-md5", result.getFileMd5());
        assertEquals(7, result.getChunkId());
        assertEquals(2d / 62, result.getScore(), 1e-12);
        assertEquals("evidence.pdf", result.getFileName());
        assertEquals(3, result.getPageNumber());
        assertEquals("page anchor", result.getAnchorText());
        assertEquals("matched evidence", result.getMatchedChunkText());
        assertEquals("HYBRID", result.getRetrievalMode());
    }

    @Test
    void embeddingFailureFallsBackToPermissionFilteredTextSearch() {
        when(embedding.embed(anyList(), eq("1"), eq(EmbeddingClient.UsageType.QUERY)))
                .thenThrow(new RuntimeException("provider unavailable"));
        assertTrue(service.searchWithPermission("question", "1", 5).isEmpty());
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).toString().contains("TEAM_A"));
        assertFalse(requests.get(0).toString().contains("PRIVATE_bob"));
    }

    @Test
    void totalRetrievalFailureIsReportedInsteadOfAnEmptySuccess() throws Exception {
        doThrow(new IOException("ES unavailable")).when(transport).performRequest(any(SearchRequest.class), any(), any());
        CustomException error = assertThrows(CustomException.class,
                () -> service.searchWithPermission("question", "1", 5));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    }
}
