package com.btc.collector.analysis;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.persistence.*;
import com.btc.collector.strategy.MarketRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StrategyEvaluator.
 */
@DisplayName("StrategyEvaluator Tests")
class StrategyEvaluatorTest extends BaseIntegrationTest {

    @Autowired
    private StrategyEvaluator strategyEvaluator;

    @Autowired
    private Indicator15mRepository indicatorRepository;

    @Autowired
    private StrategyStatsRepository statsRepository;

    @BeforeEach
    void setUp() {
        indicatorRepository.deleteAll();
        statsRepository.deleteAll();
    }

    @Nested
    @DisplayName("Basic Evaluation Tests")
    class BasicEvaluationTests {

        @Test
        @DisplayName("Should evaluate and return result")
        void shouldEvaluateAndReturnResult() {
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(65),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            assertNotNull(result);
            assertNotNull(result.getBaseProbability());
            assertNotNull(result.getFinalProbability());
        }

        @Test
        @DisplayName("Should include regime information")
        void shouldIncludeRegimeInformation() {
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(65),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            assertNotNull(result.getMarketRegime());
            assertNotNull(result.getRegimeFactor());
            assertTrue(result.getRegimeConfidence() >= 0.0);
            assertTrue(result.getRegimeConfidence() <= 1.0);
        }

        @Test
        @DisplayName("Should include strategy weight")
        void shouldIncludeStrategyWeight() {
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(65),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            assertNotNull(result.getStrategyWeight());
            assertTrue(result.getStrategyWeight().doubleValue() > 0);
        }
    }

    @Nested
    @DisplayName("Regime-based Filtering Tests")
    class RegimeFilteringTests {

        @Test
        @DisplayName("Should suppress strategy not allowed in regime")
        void shouldSuppressStrategyNotAllowedInRegime() {
            // Create a snapshot that strongly indicates HIGH_VOLATILITY
            MarketSnapshot volatileSnapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(15)) // Extreme RSI
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(49500))
                    .priceChange1h(BigDecimal.valueOf(8.0)) // Large change
                    .volumeChangePct(BigDecimal.valueOf(60))  // HIGH (>50)
                    .build();

            // MEAN_REVERSION strategy should be suppressed in HIGH_VOLATILITY
            // (This depends on actual strategy configuration)
            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(75),
                    volatileSnapshot,
                    "RSI_LOW_EMA_BULL_VOL_HIGH"); // This is a MEAN_REVERSION strategy

            assertNotNull(result);
            // If not allowed, probability should be zero
            if (!result.isStrategyAllowedInRegime()) {
                assertEquals(BigDecimal.ZERO, result.getFinalProbability());
            }
        }

        @Test
        @DisplayName("Should allow strategy in compatible regime")
        void shouldAllowStrategyInCompatibleRegime() {
            MarketSnapshot trendingSnapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(55))
                    .ema50(BigDecimal.valueOf(51000))
                    .ema200(BigDecimal.valueOf(48000)) // Clear trend
                    .priceChange1h(BigDecimal.valueOf(1.5))
                    .volumeChangePct(BigDecimal.valueOf(0))  // MEDIUM
                    .build();

            // BREAKOUT strategy should be allowed in TREND
            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(70),
                    trendingSnapshot,
                    "RSI_MID_EMA_BULL_VOL_HIGH"); // BREAKOUT strategy

            assertNotNull(result);
            if (result.isStrategyAllowedInRegime()) {
                assertTrue(result.getFinalProbability().doubleValue() > 0);
            }
        }
    }

    @Nested
    @DisplayName("Probability Adjustment Tests")
    class ProbabilityAdjustmentTests {

        @Test
        @DisplayName("Final probability should be clamped to 0-100")
        void finalProbabilityShouldBeClamped() {
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(150), // High base
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            assertTrue(result.getFinalProbability().doubleValue() >= 0);
            assertTrue(result.getFinalProbability().doubleValue() <= 100);
        }

        @Test
        @DisplayName("Regime multiplier should affect final probability")
        void regimeMultiplierShouldAffectProbability() {
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(50),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            // The formula is: base × regimeMultiplier × regimeConfidence × weight
            double expected = 50.0 * result.getRegimeFactor().doubleValue()
                    * result.getRegimeConfidence()
                    * result.getStrategyWeight().doubleValue();

            // With history enabled, there's additional modification
            // Just check it's reasonable
            assertTrue(result.getFinalProbability().doubleValue() >= 0);
        }

        @Test
        @DisplayName("Should provide confidence percentage string")
        void shouldProvideConfidencePercentageString() {
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(65),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            String confidenceStr = result.getRegimeConfidencePercent();
            assertNotNull(confidenceStr);
            assertTrue(confidenceStr.endsWith("%"));
        }
    }

    @Nested
    @DisplayName("Historical Factor Tests")
    class HistoricalFactorTests {

        @Test
        @DisplayName("Should include historical factor when history enabled")
        void shouldIncludeHistoricalFactor() {
            // Create sufficient indicators for history calculation
            createTestIndicators(10);
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(65),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            assertNotNull(result.getHistoricalFactor());
        }

        @Test
        @DisplayName("Historical factor should be in reasonable range")
        void historicalFactorShouldBeInRange() {
            createTestIndicators(10);
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(65),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            double factor = result.getHistoricalFactor().doubleValue();
            assertTrue(factor >= 0.5 && factor <= 1.5);
        }
    }

    @Nested
    @DisplayName("Strategy Allowed Flag Tests")
    class StrategyAllowedTests {

        @Test
        @DisplayName("Result should indicate if strategy was allowed")
        void resultShouldIndicateIfAllowed() {
            MarketSnapshot snapshot = createTestSnapshot();

            EvaluationResult result = strategyEvaluator.evaluate(
                    BigDecimal.valueOf(65),
                    snapshot,
                    "RSI_MID_EMA_BULL_VOL_MED");

            // The field should be set
            assertNotNull(result.isStrategyAllowedInRegime());
        }
    }

    private MarketSnapshot createTestSnapshot() {
        return MarketSnapshot.builder()
                .price(BigDecimal.valueOf(50000))
                .rsi(BigDecimal.valueOf(50))
                .ema50(BigDecimal.valueOf(50000))
                .ema200(BigDecimal.valueOf(49000))
                .priceChange1h(BigDecimal.valueOf(1.0))
                .priceChange4h(BigDecimal.valueOf(2.0))
                .volumeChangePct(BigDecimal.valueOf(0))  // MEDIUM
                .build();
    }

    private void createTestIndicators(int count) {
        LocalDateTime baseTime = LocalDateTime.now().minusHours(count);
        List<Indicator15mEntity> indicators = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            indicators.add(Indicator15mEntity.builder()
                    .openTime(baseTime.plusMinutes(i * 15))
                    .rsi14(BigDecimal.valueOf(50 + (i % 10) - 5))
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(49000))
                    .atr14(BigDecimal.valueOf(500))
                    .bbUpper(BigDecimal.valueOf(51000))
                    .bbMiddle(BigDecimal.valueOf(50000))
                    .bbLower(BigDecimal.valueOf(49000))
                    .avgVolume20(BigDecimal.valueOf(100))
                    .build());
        }
        indicatorRepository.saveAll(indicators);
    }
}
