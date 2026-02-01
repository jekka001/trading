package com.btc.collector.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MarketRegimeResult.
 */
@DisplayName("MarketRegimeResult Tests")
class MarketRegimeResultTest {

    @Test
    @DisplayName("Should calculate effective multiplier correctly")
    void shouldCalculateEffectiveMultiplier() {
        MarketRegimeResult result = MarketRegimeResult.builder()
                .regime(MarketRegime.TREND)
                .confidence(0.75)
                .build();

        // TREND multiplier is 1.1, so effective = 1.1 * 0.75 = 0.825
        assertEquals(0.825, result.getEffectiveMultiplier(), 0.001);
    }

    @Test
    @DisplayName("Should format confidence as percentage")
    void shouldFormatConfidenceAsPercentage() {
        MarketRegimeResult result = MarketRegimeResult.builder()
                .regime(MarketRegime.RANGE)
                .confidence(0.8)
                .build();

        assertEquals("80%", result.getConfidencePercent());
    }

    @Test
    @DisplayName("Should store matched and total conditions")
    void shouldStoreConditions() {
        MarketRegimeResult result = MarketRegimeResult.builder()
                .regime(MarketRegime.HIGH_VOLATILITY)
                .confidence(0.5)
                .matchedConditions(2)
                .totalConditions(4)
                .detectedAt(LocalDateTime.now())
                .build();

        assertEquals(2, result.getMatchedConditions());
        assertEquals(4, result.getTotalConditions());
    }

    @Test
    @DisplayName("Should handle zero confidence")
    void shouldHandleZeroConfidence() {
        MarketRegimeResult result = MarketRegimeResult.builder()
                .regime(MarketRegime.TREND)
                .confidence(0.0)
                .build();

        assertEquals(0.0, result.getEffectiveMultiplier());
        assertEquals("0%", result.getConfidencePercent());
    }

    @Test
    @DisplayName("Should handle full confidence")
    void shouldHandleFullConfidence() {
        MarketRegimeResult result = MarketRegimeResult.builder()
                .regime(MarketRegime.TREND)
                .confidence(1.0)
                .build();

        assertEquals(1.1, result.getEffectiveMultiplier(), 0.001); // TREND multiplier
        assertEquals("100%", result.getConfidencePercent());
    }
}
