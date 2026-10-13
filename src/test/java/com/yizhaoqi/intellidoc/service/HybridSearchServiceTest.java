package com.yizhaoqi.intellidoc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.intellidoc.client.EmbeddingClient;
import com.yizhaoqi.intellidoc.config.HybridSearchProperties;
import com.yizhaoqi.intellidoc.entity.EsDocument;
import com.yizhaoqi.intellidoc.exception.CustomException;
import com.yizhaoqi.intellidoc.model.User;
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
