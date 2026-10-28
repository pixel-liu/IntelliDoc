package com.yizhaoqi.intellidoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHandlerNoKnowledgeTest {

    @Test
    void emptySearchStreamsFixedResponseAndStopsBeforeAnotherModelTurn() throws Exception {
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        ChatGenerationStateService generationState = mock(ChatGenerationStateService.class);
        LlmProviderRouter llmProviderRouter = mock(LlmProviderRouter.class);
        ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
        var toolResult = new AgentToolRegistry.ToolExecutionResult(
                "search_knowledge",
                true,
                AgentToolRegistry.NO_KNOWLEDGE_RESULT,
                Map.of("results", List.of())
        );
        when(toolRegistry.executeTool(eq("search_knowledge"), anyMap(), eq("1"), isNull()))
                .thenReturn(toolResult);

        ChatHandler handler = new ChatHandler(
                mock(RedisTemplate.class),
                mock(HybridSearchService.class),
                llmProviderRouter,
                mock(RateLimitService.class),
                mock(ConversationService.class),
                generationState,
                sessionRegistry,
                toolRegistry,
                mock(CoreferenceResolutionService.class),
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class)
        );

        Object executed = ReflectionTestUtils.invokeMethod(
                handler,
                "executeToolForReAct",
                "1",
                "question",
                "generation-1",
                "conversation-1",
                new LlmProviderRouter.ToolCallDecision("call-1", "search_knowledge", Map.of("query", "question"))
        );

        Method content = executed.getClass().getDeclaredMethod("content");
        Method streamedToUser = executed.getClass().getDeclaredMethod("streamedToUser");
        content.setAccessible(true);
        streamedToUser.setAccessible(true);
        assertEquals(AgentToolRegistry.NO_KNOWLEDGE_RESULT, content.invoke(executed));
        assertTrue((Boolean) streamedToUser.invoke(executed));
        verify(generationState).updateReferenceMappings("generation-1", Map.of());
        verify(generationState).appendChunk("generation-1", AgentToolRegistry.NO_KNOWLEDGE_RESULT);
        verifyNoInteractions(llmProviderRouter);
    }
}
