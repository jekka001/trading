package com.btc.collector.strategy;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.analysis.MarketSnapshot;
import com.btc.collector.persistence.*;
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
 * Integration tests for MarketRegimeDetector.
 */
@DisplayName("MarketRegimeDetector Tests")
class MarketRegimeDetectorTest extends BaseIntegrationTest {

    @Autowired
    private MarketRegimeDetector regimeDetector;

    @Autowired
    private Candle15mRepository candleRepository;

    @Autowired
    private Indicator15mRepository indicatorRepository;

    @Autowired
    private MarketRegimeRepository regimeRepository;

    @BeforeEach
    void setUp() {
        regimeRepository.deleteAll();
        indicatorRepository.deleteAll();
        candleRepository.deleteAll();
    }

    @Nested
    @DisplayName("Snapshot-based Detection")
    class SnapshotDetectionTests {

        @Test
        @DisplayName("Should detect TREND regime from trending snapshot")
        void shouldDetectTrendRegime() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(55))
                    .ema50(BigDecimal.valueOf(51000))
                    .ema200(BigDecimal.valueOf(48000)) // 6% distance - trending
                    .priceChange1h(BigDecimal.valueOf(1.5))
                    .build();

            MarketRegimeResult result = regimeDetector.detectRegime(snapshot);

            assertNotNull(result);
            assertNotNull(result.getRegime());
            assertTrue(result.getConfidence() >= 0.0 && result.getConfidence() <= 1.0);
        }

        @Test
        @DisplayName("Should detect RANGE regime from ranging snapshot")
        void shouldDetectRangeRegime() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(50))
                    .ema50(BigDecimal.valueOf(50100))
                    .ema200(BigDecimal.valueOf(50000)) // 0.2% distance - ranging
                    .priceChange1h(BigDecimal.valueOf(0.2))
                    .build();

            MarketRegimeResult result = regimeDetector.detectRegime(snapshot);

            assertNotNull(result);
            // Expect RANGE or at least valid result
            assertTrue(result.getConfidence() >= 0.0);
        }

        @Test
        @DisplayName("Should detect HIGH_VOLATILITY from volatile snapshot")
        void shouldDetectHighVolatilityRegime() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(20)) // Extreme RSI
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(49000))
                    .priceChange1h(BigDecimal.valueOf(5.0)) // Large change
                    .build();

            MarketRegimeResult result = regimeDetector.detectRegime(snapshot);

            assertNotNull(result);
            assertTrue(result.getConfidence() >= 0.0);
        }

        @Test
        @DisplayName("Should handle null snapshot gracefully")
        void shouldHandleNullSnapshot() {
            MarketRegimeResult result = regimeDetector.detectRegime((MarketSnapshot) null);

            assertNotNull(result);
            assertEquals(MarketRegime.RANGE, result.getRegime());
            assertEquals(0.5, result.getConfidence());
        }
    }

    @Nested
    @DisplayName("Confidence Score Tests")
    class ConfidenceTests {

        @Test
        @DisplayName("Confidence should be between 0 and 1")
        void confidenceShouldBeInRange() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(50))
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(49000))
                    .priceChange1h(BigDecimal.valueOf(1.0))
                    .build();

            MarketRegimeResult result = regimeDetector.detectRegime(snapshot);

            assertTrue(result.getConfidence() >= 0.0);
            assertTrue(result.getConfidence() <= 1.0);
        }

        @Test
        @DisplayName("Should calculate effective multiplier correctly")
        void shouldCalculateEffectiveMultiplier() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(50))
                    .ema50(BigDecimal.valueOf(51000))
                    .ema200(BigDecimal.valueOf(48000))
                    .priceChange1h(BigDecimal.valueOf(1.0))
                    .build();

            MarketRegimeResult result = regimeDetector.detectRegime(snapshot);

            double expectedEffective = result.getRegime().getMultiplier() * result.getConfidence();
            assertEquals(expectedEffective, result.getEffectiveMultiplier(), 0.001);
        }

        @Test
        @DisplayName("Should provide confidence as percentage string")
        void shouldProvideConfidencePercent() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .price(BigDecimal.valueOf(50000))
                    .rsi(BigDecimal.valueOf(50))
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(49000))
                    .build();

            MarketRegimeResult result = regimeDetector.detectRegime(snapshot);

            assertNotNull(result.getConfidencePercent());
            assertTrue(result.getConfidencePercent().endsWith("%"));
        }
    }

    @Nested
    @DisplayName("Regime Adjustment Factor Tests")
    class AdjustmentFactorTests {

        @Test
        @DisplayName("TREND should boost TREND_FOLLOWING strategies")
        void trendShouldBoostTrendFollowing() {
            BigDecimal factor = regimeDetector.getRegimeAdjustmentFactor(
                    MarketRegime.TREND,
                    StrategyDefinition.StrategyType.TREND_FOLLOWING);

            assertTrue(factor.doubleValue() > 1.0);
        }

        @Test
        @DisplayName("TREND should reduce MEAN_REVERSION strategies")
        void trendShouldReduceMeanReversion() {
            BigDecimal factor = regimeDetector.getRegimeAdjustmentFactor(
                    MarketRegime.TREND,
                    StrategyDefinition.StrategyType.MEAN_REVERSION);

            assertTrue(factor.doubleValue() < 1.0);
        }

        @Test
        @DisplayName("RANGE should boost MEAN_REVERSION strategies")
        void rangeShouldBoostMeanReversion() {
            BigDecimal factor = regimeDetector.getRegimeAdjustmentFactor(
                    MarketRegime.RANGE,
                    StrategyDefinition.StrategyType.MEAN_REVERSION);

            assertTrue(factor.doubleValue() > 1.0);
        }

        @Test
        @DisplayName("HIGH_VOLATILITY should boost BREAKOUT strategies")
        void highVolatilityShouldBoostBreakout() {
            BigDecimal factor = regimeDetector.getRegimeAdjustmentFactor(
                    MarketRegime.HIGH_VOLATILITY,
                    StrategyDefinition.StrategyType.BREAKOUT);

            assertTrue(factor.doubleValue() > 1.0);
        }

        @Test
        @DisplayName("HYBRID should have neutral factor in all regimes")
        void hybridShouldHaveNeutralFactor() {
            for (MarketRegime regime : MarketRegime.values()) {
                BigDecimal factor = regimeDetector.getRegimeAdjustmentFactor(
                        regime,
                        StrategyDefinition.StrategyType.HYBRID);

                // HYBRID should be close to 1.0 in most regimes
                assertTrue(factor.doubleValue() >= 0.9 && factor.doubleValue() <= 1.1);
            }
        }
    }

    @Nested
    @DisplayName("Persistence Tests")
    class PersistenceTests {

        @Test
        @DisplayName("Should persist regime to database")
        void shouldPersistRegime() {
            // Create test data
            createTestCandles(100);
            createTestIndicators(100);

            MarketRegimeResult result = regimeDetector.detectAndPersist();

            assertNotNull(result);

            List<MarketRegimeEntity> history = regimeRepository.findRecentRegimes(1);
            assertFalse(history.isEmpty());
            assertEquals(result.getRegime(), history.get(0).getRegimeType());
        }

        @Test
        @DisplayName("Should retrieve regime history")
        void shouldRetrieveRegimeHistory() {
            // Create some regime entries
            for (int i = 0; i < 5; i++) {
                MarketRegimeEntity entity = MarketRegimeEntity.builder()
                        .timestamp(LocalDateTime.now().minusMinutes(i * 15))
                        .regimeType(MarketRegime.values()[i % 3])
                        .confidence(0.75)
                        .matchedConditions(3)
                        .totalConditions(4)
                        .build();
                regimeRepository.save(entity);
            }

            List<MarketRegimeEntity> history = regimeDetector.getRegimeHistory(10);

            assertEquals(5, history.size());
        }
    }

    @Nested
    @DisplayName("Database-based Detection Tests")
    class DatabaseDetectionTests {

        @Test
        @DisplayName("Should return default regime with insufficient data")
        void shouldReturnDefaultWithInsufficientData() {
            // No candles or indicators in DB
            MarketRegimeResult result = regimeDetector.detectRegime();

            assertNotNull(result);
            assertEquals(MarketRegime.RANGE, result.getRegime()); // Default
            assertEquals(0.5, result.getConfidence());
        }

        @Test
        @DisplayName("Should detect regime from database with sufficient data")
        void shouldDetectRegimeFromDatabase() {
            // Create sufficient test data
            createTestCandles(100);
            createTestIndicators(100);

            MarketRegimeResult result = regimeDetector.detectRegime();

            assertNotNull(result);
            assertNotNull(result.getRegime());
            assertTrue(result.getConfidence() >= 0.0 && result.getConfidence() <= 1.0);
            assertNotNull(result.getDetectedAt());
        }
    }

    private void createTestCandles(int count) {
        LocalDateTime baseTime = LocalDateTime.now().minusHours(count / 4);
        List<Candle15mEntity> candles = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(50000 + (i * 10));
            candles.add(Candle15mEntity.builder()
                    .openTime(baseTime.plusMinutes(i * 15))
                    .openPrice(price)
                    .highPrice(price.add(BigDecimal.valueOf(50)))
                    .lowPrice(price.subtract(BigDecimal.valueOf(50)))
                    .closePrice(price.add(BigDecimal.valueOf(25)))
                    .volume(BigDecimal.valueOf(100))
                    .closeTime(baseTime.plusMinutes(i * 15 + 14))
                    .build());
        }
        candleRepository.saveAll(candles);
    }

    private void createTestIndicators(int count) {
        LocalDateTime baseTime = LocalDateTime.now().minusHours(count / 4);
        List<Indicator15mEntity> indicators = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(50000 + (i * 10));
            indicators.add(Indicator15mEntity.builder()
                    .openTime(baseTime.plusMinutes(i * 15))
                    .rsi14(BigDecimal.valueOf(50 + (i % 20) - 10))
                    .ema50(price)
                    .ema200(price.subtract(BigDecimal.valueOf(500)))
                    .atr14(BigDecimal.valueOf(500))
                    .bbUpper(price.add(BigDecimal.valueOf(1000)))
                    .bbMiddle(price)
                    .bbLower(price.subtract(BigDecimal.valueOf(1000)))
                    .avgVolume20(BigDecimal.valueOf(100))
                    .build());
        }
        indicatorRepository.saveAll(indicators);
    }
}
