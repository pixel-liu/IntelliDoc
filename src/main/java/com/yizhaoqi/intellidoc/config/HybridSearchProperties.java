package com.yizhaoqi.intellidoc.config;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Hybrid retrieval defaults to reciprocal rank fusion (RRF). */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "search.hybrid")
public class HybridSearchProperties {
    public enum Fusion { WEIGHTED, RRF }
    @NotNull private Fusion fusion = Fusion.RRF;
    @DecimalMin("0.0") @DecimalMax("1.0") private double knnWeight = 0.3d;
    @DecimalMin("0.0") @DecimalMax("1.0") private double bm25Weight = 0.7d;
    @Min(1) @Max(100) private int recallMultiplier = 30;
    @Min(1) @Max(1000) private int rrfRankConstant = 60;
    @AssertTrue(message = "At least one search weight must be positive")
    public boolean isWeightSumPositive() { return knnWeight + bm25Weight > 0; }
}
