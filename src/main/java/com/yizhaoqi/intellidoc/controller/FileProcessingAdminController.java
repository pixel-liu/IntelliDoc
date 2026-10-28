package com.yizhaoqi.intellidoc.controller;

import com.yizhaoqi.intellidoc.model.FileProcessingEvent;
import com.yizhaoqi.intellidoc.exception.CustomException;
import com.yizhaoqi.intellidoc.repository.FileProcessingEventRepository;
import com.yizhaoqi.intellidoc.repository.UserRepository;
import com.yizhaoqi.intellidoc.service.FileProcessingOutboxService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.security.Principal;

/** Protected by the existing admin-path ADMIN security rule. */
@RestController
@RequestMapping("/api/v1/admin/file-processing")
public class FileProcessingAdminController {
    private final FileProcessingEventRepository events;
    private final FileProcessingOutboxService outbox;
    private final UserRepository users;

    public FileProcessingAdminController(FileProcessingEventRepository events, FileProcessingOutboxService outbox,
                                         UserRepository users) {
        this.events = events;
        this.outbox = outbox;
        this.users = users;
    }

    @GetMapping("/dead-letters")
    public Map<String, Object> deadLetters(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        var result = events.findByProcessingStatusOrderByCreatedAtDesc(FileProcessingEvent.DEAD,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size))));
        return Map.of("code", 200, "data", result.map(event -> {
            var task = outbox.readTask(event);
            // Do not return the raw payload or storage credentials/URLs.
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("taskId", event.getId());
            row.put("fileMd5", task.getFileMd5());
            row.put("fileName", task.getFileName());
            row.put("userId", task.getUserId());
            row.put("orgTag", task.getOrgTag());
            row.put("lastError", event.getLastError());
            row.put("createdAt", event.getCreatedAt());
            row.put("publishAttempts", event.getPublishAttempts());
            return row;
        }));
    }

    @PostMapping("/{taskId}/replay")
    public Map<String, Object> replay(@PathVariable String taskId, Principal principal) {
        String userId = users.findByUsername(principal.getName()).orElseThrow().getId().toString();
        return Map.of("code", 200, "message", "死信重放任务已可靠入队",
                "data", Map.of("taskId", outbox.replay(taskId, userId)));
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<Map<String, Object>> processingError(CustomException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "code", exception.getStatus().value(), "message", exception.getMessage()));
    }
}
