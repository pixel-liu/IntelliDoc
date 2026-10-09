package com.yizhaoqi.intellidoc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.intellidoc.model.Conversation;
import com.yizhaoqi.intellidoc.model.ConversationSession;
import com.yizhaoqi.intellidoc.model.User;
import com.yizhaoqi.intellidoc.repository.*;
import com.yizhaoqi.intellidoc.service.ConversationService;
import com.yizhaoqi.intellidoc.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationOwnershipTest {
    private ConversationSessionRepository sessions;
    private ConversationRepository messages;
    private UserRepository users;
    private ConversationService service;
    private MockMvc mvc;
    private RedisTemplate<String, String> redis;
    private ValueOperations<String, String> values;
    private ConversationSession owned;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        sessions = mock(ConversationSessionRepository.class);
        messages = mock(ConversationRepository.class);
        users = mock(UserRepository.class);
        redis = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new ConversationService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessions);
        ReflectionTestUtils.setField(service, "conversationRepository", messages);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        User owner = new User(); owner.setId(1L);
        owned = new ConversationSession(); owned.setUser(owner); owned.setConversationId("mine");
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(sessions.findByConversationIdAndUserId("mine", 1L)).thenReturn(Optional.of(owned));
        when(sessions.findByConversationId("mine")).thenReturn(Optional.of(owned));
        JwtUtils jwt = mock(JwtUtils.class);
        when(jwt.extractUsernameFromToken("user1")).thenReturn("alice");
        when(jwt.extractUserIdFromToken("user1")).thenReturn("1");
        ConversationController history = new ConversationController();
        ConversationSessionController actions = new ConversationSessionController();
        for (Object controller : List.of(history, actions)) {
            ReflectionTestUtils.setField(controller, "jwtUtils", jwt);
            ReflectionTestUtils.setField(controller, "conversationService", service);
        }
        mvc = MockMvcBuilders.standaloneSetup(history, actions).build();
    }

    @Test
    void foreignHistoryIsNotDisclosed() throws Exception {
        mvc.perform(get("/api/v1/users/conversation").param("conversationId", "foreign")
                .header("Authorization", "Bearer user1")).andExpect(status().isNotFound());
        verifyNoInteractions(messages, redis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch", "archive", "unarchive"})
    void foreignSessionCannotBeChanged(String operation) throws Exception {
        mvc.perform(put("/api/v1/users/conversations/foreign/" + operation)
                .header("Authorization", "Bearer user1")).andExpect(status().isNotFound());
        verify(sessions, never()).save(any());
        verifyNoInteractions(redis);
    }

    @Test
    void ownerHistoryRetainsPersistedReferenceMappings() throws Exception {
        Conversation message = new Conversation();
        message.setId(1L); message.setQuestion("question"); message.setAnswer("answer");
        message.setConversationId("mine"); message.setTimestamp(LocalDateTime.now());
        message.setReferenceMappingsJson("{\"1\":{\"fileMd5\":\"abc\",\"pageNumber\":2}}");
        when(messages.findByConversationIdAndUserIdOrderByTimestampAsc("mine", 1L)).thenReturn(List.of(message));
        mvc.perform(get("/api/v1/users/conversation").param("conversationId", "mine")
                .header("Authorization", "Bearer user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].referenceMappings.1.pageNumber").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch", "archive", "unarchive"})
    void ownerCanChangeOwnSession(String operation) throws Exception {
        mvc.perform(put("/api/v1/users/conversations/mine/" + operation)
                .header("Authorization", "Bearer user1")).andExpect(status().isOk());
        if (operation.equals("switch")) verify(values).set(eq("user:1:current_conversation"), eq("mine"), any(java.time.Duration.class));
        else verify(sessions).save(owned);
    }

    @Test
    void staleRedisConversationCannotBeClaimedByAnotherUser() {
        assertThrows(com.yizhaoqi.intellidoc.exception.CustomException.class,
                () -> service.ensureConversationSession(2L, "mine", "title"));
        verify(sessions, never()).save(any());
    }

    @Test
    void foreignConversationCannotReceivePersistedMessages() {
        assertThrows(com.yizhaoqi.intellidoc.exception.CustomException.class,
                () -> service.recordConversation(1L, "question", "answer", "foreign", Map.of()));
        verifyNoInteractions(messages);
    }
}
