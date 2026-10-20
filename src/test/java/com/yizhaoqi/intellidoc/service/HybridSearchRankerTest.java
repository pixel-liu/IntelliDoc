package com.yizhaoqi.intellidoc.service;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.intellidoc.config.HybridSearchProperties;
import com.yizhaoqi.intellidoc.entity.EsDocument;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HybridSearchRankerTest {
    private Hit<EsDocument> hit(String id, double score) {
        EsDocument doc = new EsDocument(); doc.setId(id); doc.setFileMd5(id); doc.setChunkId(0);
        return Hit.of(h -> h.index("knowledge_base").id(id).score(score).source(doc));
    }
    @Test
    void explicitWeightedFusionRetainsMinMaxRanking() {
        var properties = new HybridSearchProperties();
        properties.setFusion(HybridSearchProperties.Fusion.WEIGHTED);
        var ranked = new HybridSearchRanker(properties).rank(
                List.of(hit("a", 10), hit("b", 0)), List.of(hit("b", 20), hit("a", 0)), 5);
        assertEquals(List.of("b", "a"), ranked.stream().map(HybridSearchRanker.RankedDocument::key).toList());
        assertEquals(.7, ranked.get(0).score(), 1e-12);
        assertEquals(.3, ranked.get(1).score(), 1e-12);
    }
    @Test
    void defaultRrfCombinesRanksRegardlessOfIncompatibleScoreScales() {
        var properties = new HybridSearchProperties();
        var ranked = new HybridSearchRanker(properties).rank(
                List.of(hit("a", .99), hit("b", .98), hit("c", .97)),
                List.of(hit("b", 1000), hit("c", 5), hit("a", 1)), 2);
        assertEquals("b", ranked.get(0).key());
        assertEquals(1d / 61 + 1d / 62, ranked.get(0).score(), 1e-12);
        assertEquals(2, ranked.size());
    }
    @Test
    void tiesAreStableAndDuplicateHitsDoNotInflateRrf() {
        var properties = new HybridSearchProperties(); properties.setFusion(HybridSearchProperties.Fusion.RRF);
        var ranked = new HybridSearchRanker(properties).rank(
                List.of(hit("a", 1), hit("a", 1)), List.of(hit("b", 1)), 5);
        assertEquals(List.of("a", "b"), ranked.stream().map(HybridSearchRanker.RankedDocument::key).toList());
        assertEquals(ranked.get(0).score(), ranked.get(1).score());
        assertTrue(new HybridSearchRanker(properties).rank(List.of(), List.of(), 5).isEmpty());
    }
    @Test
    void invalidExperimentalConfigurationIsRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var properties = new HybridSearchProperties();
            assertTrue(factory.getValidator().validate(properties).isEmpty());
            properties.setKnnWeight(0); properties.setBm25Weight(0);
            properties.setRecallMultiplier(0);
            assertEquals(2, factory.getValidator().validate(properties).size());
        }
    }
}
