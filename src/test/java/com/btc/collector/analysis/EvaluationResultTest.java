package com.btc.collector.analysis;

import com.btc.collector.strategy.MarketRegime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EvaluationResult.
 */
@DisplayName("EvaluationResult Tests")
class EvaluationResultTest {

    @Test
    @DisplayName("Should build with all fields")
    void shouldBuildWithAllFields() {
        EvaluationResult result = EvaluationResult.builder()
                .baseProbability(BigDecimal.valueOf(65))
                .strategyWeight(BigDecimal.valueOf(0.8))
                .historicalFactor(BigDecimal.valueOf(1.1))
                .regimeFactor(BigDecimal.valueOf(1.05))
                .regimeConfidence(0.75)
                .marketRegime(MarketRegime.TREND)
                .finalProbability(BigDecimal.valueOf(55))
                .historyEnabled(true)
                .strategyAllowedInRegime(true)
                .build();

        assertEquals(BigDecimal.valueOf(65), result.getBaseProbability());
        assertEquals(BigDecimal.valueOf(0.8), result.getStrategyWeight());
        assertEquals(BigDecimal.valueOf(1.1), result.getHistoricalFactor());
        assertEquals(BigDecimal.valueOf(1.05), result.getRegimeFactor());
        assertEquals(0.75, result.getRegimeConfidence());
        assertEquals(MarketRegime.TREND, result.getMarketRegime());
        assertEquals(BigDecimal.valueOf(55), result.getFinalProbability());
        assertTrue(result.isHistoryEnabled());
        assertTrue(result.isStrategyAllowedInRegime());
    }

    @Test
    @DisplayName("Should format regime confidence as percentage")
    void shouldFormatConfidenceAsPercentage() {
        EvaluationResult result = EvaluationResult.builder()
                .regimeConfidence(0.82)
                .build();

        assertEquals("82%", result.getRegimeConfidencePercent());
    }

    @Test
    @DisplayName("Should handle zero confidence")
    void shouldHandleZeroConfidence() {
        EvaluationResult result = EvaluationResult.builder()
                .regimeConfidence(0.0)
                .build();

        assertEquals("0%", result.getRegimeConfidencePercent());
    }

    @Test
    @DisplayName("Should handle full confidence")
    void shouldHandleFullConfidence() {
        EvaluationResult result = EvaluationResult.builder()
                .regimeConfidence(1.0)
                .build();

        assertEquals("100%", result.getRegimeConfidencePercent());
    }

    @Test
    @DisplayName("Should default strategyAllowedInRegime to true")
    void shouldDefaultStrategyAllowedToTrue() {
        EvaluationResult result = EvaluationResult.builder()
                .baseProbability(BigDecimal.valueOf(50))
                .build();

        assertTrue(result.isStrategyAllowedInRegime());
    }

    @Test
    @DisplayName("Should allow setting strategyAllowedInRegime to false")
    void shouldAllowSettingStrategyNotAllowed() {
        EvaluationResult result = EvaluationResult.builder()
                .baseProbability(BigDecimal.valueOf(50))
                .strategyAllowedInRegime(false)
                .build();

        assertFalse(result.isStrategyAllowedInRegime());
    }
}
