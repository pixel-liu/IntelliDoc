package com.yizhaoqi.intellidoc.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HybridSearchPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SearchConfiguration.class)
            .withInitializer(context -> {
                try {
                    // The test classpath has its own application.yml; verify the production defaults.
                    var sources = new YamlPropertySourceLoader().load("application",
                            new FileSystemResource("src/main/resources/application.yml"));
                    sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

    @Test
    void applicationDefaultsToRrf() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("RRF", context.getEnvironment().getProperty("search.hybrid.fusion"));
            var properties = context.getBean(HybridSearchProperties.class);
            assertEquals(HybridSearchProperties.Fusion.RRF, properties.getFusion());
            assertEquals(60, properties.getRrfRankConstant());
            assertEquals(0.02d, properties.getRrfMinScore());
            assertEquals(0.3d, properties.getWeightedMinScore());
            assertEquals(0.02d, properties.minimumScore());
        });
    }

    @Test
    void weightedFusionRemainsAnExplicitOverride() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("searchEnvironment", Map.of("SEARCH_HYBRID_FUSION", "WEIGHTED"))))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("WEIGHTED", context.getEnvironment().getProperty("search.hybrid.fusion"));
                    assertEquals(HybridSearchProperties.Fusion.WEIGHTED,
                            context.getBean(HybridSearchProperties.class).getFusion());
                    assertEquals(0.3d, context.getBean(HybridSearchProperties.class).minimumScore());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HybridSearchProperties.class)
    static class SearchConfiguration {}
}
