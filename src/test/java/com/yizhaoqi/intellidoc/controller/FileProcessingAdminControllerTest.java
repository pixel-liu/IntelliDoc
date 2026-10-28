package com.yizhaoqi.intellidoc.controller;

import com.yizhaoqi.intellidoc.config.JwtAuthenticationFilter;
import com.yizhaoqi.intellidoc.config.OrgTagAuthorizationFilter;
import com.yizhaoqi.intellidoc.config.SecurityConfig;
import com.yizhaoqi.intellidoc.exception.CustomException;
import com.yizhaoqi.intellidoc.model.User;
import com.yizhaoqi.intellidoc.repository.FileProcessingEventRepository;
import com.yizhaoqi.intellidoc.repository.FileUploadRepository;
import com.yizhaoqi.intellidoc.repository.UserRepository;
import com.yizhaoqi.intellidoc.service.CustomUserDetailsService;
import com.yizhaoqi.intellidoc.service.FileProcessingOutboxService;
import com.yizhaoqi.intellidoc.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileProcessingAdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OrgTagAuthorizationFilter.class})
@ActiveProfiles("test")
class FileProcessingAdminControllerTest {
    @Autowired MockMvc mvc;
    @MockBean FileProcessingEventRepository events;
    @MockBean FileProcessingOutboxService outbox;
    @MockBean UserRepository users;
    @MockBean FileUploadRepository files;
    @MockBean JwtUtils jwt;
    @MockBean CustomUserDetailsService details;

    @Test
    void ordinaryUserCannotReadOrReplayDeadLetters() throws Exception {
        mvc.perform(get("/api/v1/admin/file-processing/dead-letters").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/file-processing/task/replay").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(events, outbox);
    }

    @Test
    void anonymousCallerCannotReadDeadLetters() throws Exception {
        mvc.perform(get("/api/v1/admin/file-processing/dead-letters"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(events, outbox);
    }

    @Test
    void adminCanListAndReplayWithAuthenticatedUserId() throws Exception {
        when(events.findByProcessingStatusOrderByCreatedAtDesc(eq("DEAD"), any()))
                .thenReturn(new PageImpl<>(List.of()));
        User admin = new User();
        admin.setId(7L);
        admin.setUsername("operator");
        when(users.findByUsername("operator")).thenReturn(Optional.of(admin));
        when(outbox.replay("task", "7")).thenReturn("new-task");
        mvc.perform(get("/api/v1/admin/file-processing/dead-letters").with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/api/v1/admin/file-processing/task/replay").with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskId").value("new-task"));
        verify(outbox).replay("task", "7");
    }

    @Test
    void replayReturnsNotFoundAndConflictInsteadOfGenericServerError() throws Exception {
        User admin = new User();
        admin.setId(7L);
        when(users.findByUsername("operator")).thenReturn(Optional.of(admin));
        when(outbox.replay("missing", "7"))
                .thenThrow(new CustomException("任务不存在", HttpStatus.NOT_FOUND));
        when(outbox.replay("active", "7"))
                .thenThrow(new CustomException("只能重放死信任务", HttpStatus.CONFLICT));
        mvc.perform(post("/api/v1/admin/file-processing/missing/replay").with(user("operator").roles("ADMIN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(post("/api/v1/admin/file-processing/active/replay").with(user("operator").roles("ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }
}
