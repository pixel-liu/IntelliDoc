package com.yizhaoqi.intellidoc.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.intellidoc.client.DeepSeekClient;
import com.yizhaoqi.intellidoc.repository.FileUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AgentToolRegistryTest {
    private HybridSearchService searchService;
    private DeepSeekClient deepSeekClient;
    private AgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        searchService = mock(HybridSearchService.class);
        deepSeekClient = mock(DeepSeekClient.class);
        registry = new AgentToolRegistry(
                searchService,
                deepSeekClient,
                mock(StringRedisTemplate.class),
                mock(ElasticsearchClient.class),
                mock(FileUploadRepository.class)
        );
    }

    @Test
    void emptyFilteredSearchReturnsTheFixedNoKnowledgeMessageWithoutReferences() {
        when(searchService.searchWithPermission("missing topic", "1", 5)).thenReturn(List.of());

        var result = registry.executeTool("search_knowledge", Map.of("query", "missing topic"), "1");

        assertEquals(AgentToolRegistry.NO_KNOWLEDGE_RESULT, result.content());
        assertTrue(((List<?>) result.data().get("results")).isEmpty());
    }

    @Test
    void emptyFilteredSummaryDoesNotCallTheModel() {
        when(searchService.searchWithPermission("missing topic", "1", 5)).thenReturn(List.of());

        var result = registry.executeTool("generate_summary", Map.of("topic", "missing topic"), "1");

        assertEquals(AgentToolRegistry.NO_KNOWLEDGE_RESULT, result.content());
        assertEquals(0, result.data().get("sourceCount"));
        verifyNoInteractions(deepSeekClient);
    }
}
