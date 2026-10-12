package com.yizhaoqi.intellidoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class CoreferenceResolutionServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CoreferenceResolutionService resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        resolver = new CoreferenceResolutionService(redisTemplate, new ObjectMapper());
    }

    @Test
    void resolvesGenericPronounFromRecentConversation() {
        List<Map<String, String>> history = List.of(
                message("user", "什么是机器学习"),
                message("assistant", "机器学习是一类从数据中学习规律的方法。")
        );

        CoreferenceResolutionService.ResolutionResult result =
                resolver.resolve("conversation-1", "它有哪些应用？", history);

        assertEquals("机器学习有哪些应用？", result.resolvedMessage());
        assertEquals("机器学习", result.resolvedReferences().get(0).antecedent());
    }

    @Test
    void prefersNearestEntityOfRequestedType() {
        List<Map<String, String>> history = List.of(
                message("user", "介绍一下阿里巴巴"),
                message("assistant", "阿里巴巴是一家企业。"),
                message("user", "介绍一下张三"),
                message("assistant", "张三是一名员工。")
        );

        CoreferenceResolutionService.ResolutionResult result =
                resolver.resolve("conversation-2", "该公司有哪些业务？", history);

        assertEquals("阿里巴巴有哪些业务？", result.resolvedMessage());
        assertEquals(CoreferenceResolutionService.EntityType.ORGANIZATION,
                result.resolvedReferences().get(0).entityType());
    }

    @Test
    void usesNearestLocationWhenSeveralLocationsExist() {
        List<Map<String, String>> history = List.of(
                message("user", "介绍一下北京"),
                message("assistant", "北京是中国的首都。"),
                message("user", "介绍一下上海"),
                message("assistant", "上海是重要的经济中心。")
        );

        CoreferenceResolutionService.ResolutionResult result =
                resolver.resolve("conversation-3", "那里有哪些景点？", history);

        assertEquals("上海有哪些景点？", result.resolvedMessage());
    }

    @Test
    void prefersNearestCompleteEntityInCurrentMessage() {
        CoreferenceResolutionService.ResolutionResult result = resolver.resolve(
                "conversation-current", "机器学习和深度学习，它有哪些优势？", List.of());

        assertEquals("机器学习和深度学习，深度学习有哪些优势？", result.resolvedMessage());
    }

    @Test
    void doesNotUseEntitiesOutsideFiveUserTurnWindow() {
        List<Map<String, String>> history = new ArrayList<>();
        history.add(message("user", "什么是机器学习"));
        history.add(message("assistant", "这里是回答。"));
        for (int i = 0; i < 5; i++) {
            history.add(message("user", "谢谢"));
            history.add(message("assistant", "不客气"));
        }

        CoreferenceResolutionService.ResolutionResult result =
                resolver.resolve("conversation-4", "它有哪些应用？", history);

        assertEquals("它有哪些应用？", result.resolvedMessage());
        assertTrue(result.resolvedReferences().isEmpty());
    }

    @Test
    void keepsOriginalMessageAndStoresResolvedEntity() {
        List<Map<String, String>> history = List.of(
                message("user", "什么是RAG"),
                message("assistant", "RAG 是检索增强生成。")
        );

        CoreferenceResolutionService.ResolutionResult result =
                resolver.resolve("conversation-5", "它的优势是什么？", history);

        assertEquals("它的优势是什么？", result.originalMessage());
        assertEquals("RAG的优势是什么？", result.resolvedMessage());
        assertTrue(result.entityMemory().stream().anyMatch(entity -> "RAG".equals(entity.text())));
        assertFalse(result.entityMemory().isEmpty());
    }

    private Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }
}
