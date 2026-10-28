package com.yizhaoqi.intellidoc.service;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.intellidoc.config.HybridSearchProperties;
import com.yizhaoqi.intellidoc.entity.EsDocument;
import org.springframework.stereotype.Component;
import java.util.*;

/** Pure fusion shared by live retrieval and regression tests; no external API calls. */
@Component
public class HybridSearchRanker {
    private final HybridSearchProperties properties;
    public HybridSearchRanker(HybridSearchProperties properties) { this.properties = properties; }
    public record RankedDocument(String key, EsDocument source, double score) {}

    public List<RankedDocument> rank(List<Hit<EsDocument>> knn, List<Hit<EsDocument>> bm25, int topK) {
        Map<String, EsDocument> documents = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        addChannel(knn, properties.getKnnWeight(), documents, scores);
        addChannel(bm25, properties.getBm25Weight(), documents, scores);
        double minimumScore = properties.minimumScore();
        return scores.entrySet().stream()
                .map(item -> new RankedDocument(item.getKey(), documents.get(item.getKey()), item.getValue()))
                .filter(item -> item.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(RankedDocument::score).reversed().thenComparing(RankedDocument::key))
                .limit(topK).toList();
    }

    private void addChannel(List<Hit<EsDocument>> hits, double weight,
                            Map<String, EsDocument> documents, Map<String, Double> totals) {
        Map<String, Double> channel = new LinkedHashMap<>();
        int rank = 0;
        for (Hit<EsDocument> hit : hits) {
            if (hit.source() == null) continue;
            String key = key(hit);
            if (channel.containsKey(key)) {
                if (properties.getFusion() == HybridSearchProperties.Fusion.WEIGHTED) {
                    channel.merge(key, hit.score() == null ? 0d : hit.score(), Math::max);
                }
                continue;
            }
            documents.putIfAbsent(key, hit.source());
            rank++;
            channel.put(key, properties.getFusion() == HybridSearchProperties.Fusion.RRF
                    ? 1d / (properties.getRrfRankConstant() + rank)
                    : hit.score() == null ? 0d : hit.score());
        }
        if (properties.getFusion() == HybridSearchProperties.Fusion.WEIGHTED && !channel.isEmpty()) {
            double min = Collections.min(channel.values()), max = Collections.max(channel.values());
            channel.replaceAll((key, value) -> Double.compare(max, min) == 0 ? 1d : (value - min) / (max - min));
        }
        // Standard RRF uses equal channel contributions. Weighted fusion retains
        // the existing BM25 / vector weights and Min-Max normalization.
        double contribution = properties.getFusion() == HybridSearchProperties.Fusion.RRF ? 1d : weight;
        channel.forEach((key, score) -> totals.merge(key, contribution * score, Double::sum));
    }

    private String key(Hit<EsDocument> hit) {
        if (hit.id() != null && !hit.id().isBlank()) return hit.id();
        EsDocument source = hit.source();
        return source.getUserId() + "#" + source.getFileMd5() + "#" + source.getChunkId();
    }
}
