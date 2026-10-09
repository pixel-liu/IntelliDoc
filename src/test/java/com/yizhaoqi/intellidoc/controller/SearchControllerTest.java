package com.yizhaoqi.intellidoc.controller;

import com.yizhaoqi.intellidoc.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchControllerTest {
    @Test
    void missingIdentityCannotFallThroughToUnfilteredSearch() {
        var controller = new SearchController(); var search = mock(HybridSearchService.class);
        ReflectionTestUtils.setField(controller, "hybridSearchService", search);
        assertEquals(401, controller.hybridSearch("query", 5, null).get("code"));
        verifyNoInteractions(search);
    }
    @Test
    void excessiveOrEmptyQueriesDoNotReachRetrieval() {
        var controller = new SearchController(); var search = mock(HybridSearchService.class);
        ReflectionTestUtils.setField(controller, "hybridSearchService", search);
        assertEquals(400, controller.hybridSearch("query", 101, "1").get("code"));
        assertEquals(400, controller.hybridSearch(" ", 5, "1").get("code"));
        verifyNoInteractions(search);
    }
}
