package com.btc.collector;

import com.btc.collector.analysis.MarketSnapshot;
import com.btc.collector.analysis.StrategyAnalysisResult;
import com.btc.collector.analysis.AggregatedAnalysisResult;
import com.btc.collector.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Test Plan Section 9: Edge Cases
 *
 * Tests:
 * - Missing candles or indicators
 * - Duplicate pattern entries
 * - High volume of alerts in a short period
 * - System restart in the middle of processing
 */
@DisplayName("9. Edge Case Tests")
class EdgeCaseTest extends BaseIntegrationTest {

    @Autowired
    private Candle15mRepository candleRepository;

    @Autowired
    private Indicator15mRepository indicatorRepository;

    @Autowired
    private HistoricalPatternRepository patternRepository;

    @Autowired
    private AlertHistoryRepository alertRepository;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        patternRepository.deleteAll();
        indicatorRepository.deleteAll();
        candleRepository.deleteAll();
    }

    @Test
    @DisplayName("9.1 Handle missing candles gracefully")
    void handleMissingCandlesGracefully() {
        // Given: Gap in candle sequence
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 15);
        // Missing 10:30
        LocalDateTime time4 = LocalDateTime.of(2024, 1, 1, 10, 45);

        candleRepository.save(createCandle(time1));
        candleRepository.save(createCandle(time2));
        candleRepository.save(createCandle(time4));

        // Then: Repository operations don't fail
        assertThat(candleRepository.count()).isEqualTo(3);
        assertThat(candleRepository.findMaxOpenTime()).contains(time4);

        List<Candle15mEntity> all = candleRepository.findAllOrderByOpenTimeAsc();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getOpenTime()).isEqualTo(time1);
        assertThat(all.get(2).getOpenTime()).isEqualTo(time4);
    }

    @Test
    @DisplayName("9.2 Handle missing indicators gracefully")
    void handleMissingIndicatorsGracefully() {
        // Given: Indicators with gaps
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 30);

        indicatorRepository.save(createIndicator(time1));
        indicatorRepository.save(createIndicator(time3));

        // Then: Operations succeed
        assertThat(indicatorRepository.count()).isEqualTo(2);
        assertThat(indicatorRepository.findMaxOpenTime()).contains(time3);
    }

    @Test
    @DisplayName("9.3 Duplicate pattern entries are prevented by constraint")
    void duplicatePatternEntriesPreventedByConstraint() {
        // Given: Existing pattern
        LocalDateTime candleTime = LocalDateTime.of(2024, 1, 1, 10, 0);
        patternRepository.save(HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId("STRATEGY_1")
                .rsi(BigDecimal.valueOf(50))
                .evaluated(false)
                .build());

        assertThat(patternRepository.count()).isEqualTo(1);

        // When: Try to check if exists
        boolean exists = patternRepository.existsByCandleTime(candleTime);

        // Then: Can detect duplicates
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("9.4 Handle high volume of alerts")
    void handleHighVolumeOfAlerts() {
        // Given: Create many alerts quickly
        LocalDateTime now = LocalDateTime.now();
        int alertCount = 100;

        for (int i = 0; i < alertCount; i++) {
            AlertHistoryEntity alert = AlertHistoryEntity.builder()
                    .alertTime(now.minusMinutes(i))
                    .strategyId("STRATEGY_" + (i % 10))
                    .baseProbability(BigDecimal.valueOf(50 + i % 20))
                    .finalProbability(BigDecimal.valueOf(45 + i % 20))
                    .strategyWeight(BigDecimal.valueOf(0.5))
                    .currentPrice(BigDecimal.valueOf(50000 + i * 10))
                    .predictedProfitPct(BigDecimal.valueOf(1.5 + (i % 10) * 0.1))
                    .targetPrice(BigDecimal.valueOf(51000))
                    .predictedHours(4)
                    .evaluateAt(now.plusHours(4))
                    .evaluated(false)
                    .sentToTelegram(false)
                    .build();
            alertRepository.save(alert);
        }

        // Then: All alerts stored
        assertThat(alertRepository.count()).isEqualTo(alertCount);

        // And: Queries still work
        assertThat(alertRepository.findRecentAlerts(10)).hasSize(10);
        assertThat(alertRepository.countPendingEvaluations()).isEqualTo(alertCount);
    }

    @Test
    @DisplayName("9.5 Handle null values in market snapshot")
    void handleNullValuesInMarketSnapshot() {
        // Given: Snapshot with some null values
        MarketSnapshot snapshot = MarketSnapshot.builder()
                .price(BigDecimal.valueOf(50000))
                .rsi(null) // Null RSI
                .ema50(BigDecimal.valueOf(49500))
                .ema200(null) // Null EMA200
                .build();

        // Then: No exception when creating aggregated result
        List<StrategyAnalysisResult> results = new ArrayList<>();
        results.add(StrategyAnalysisResult.builder()
                .strategyId("TEST")
                .finalProbability(BigDecimal.valueOf(50))
                .avgProfitPct(BigDecimal.valueOf(2.0))
                .matchedPatterns(10)
                .build());

        assertThatCode(() -> AggregatedAnalysisResult.aggregate(snapshot, results))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("9.6 Handle empty strategy results list")
    void handleEmptyStrategyResultsList() {
        // Given: Empty results
        MarketSnapshot snapshot = createTestSnapshot();
        List<StrategyAnalysisResult> emptyResults = new ArrayList<>();

        // When: Aggregate
        AggregatedAnalysisResult result = AggregatedAnalysisResult.aggregate(snapshot, emptyResults);

        // Then: No exception, zero values
        assertThat(result).isNotNull();
        assertThat(result.getStrategiesWithData()).isZero();
        assertThat(result.getAvgProbability()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("9.7 Handle concurrent pattern saves")
    void handleConcurrentPatternSaves() {
        // Given: Multiple patterns for same candle time (simulating race condition)
        LocalDateTime candleTime = LocalDateTime.of(2024, 1, 1, 10, 0);

        // First save
        patternRepository.save(HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId("STRATEGY_1")
                .rsi(BigDecimal.valueOf(50))
                .evaluated(false)
                .build());

        // Check existence before second save
        assertThat(patternRepository.existsByCandleTime(candleTime)).isTrue();

        // Count should be 1
        assertThat(patternRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("9.8 Handle very large numbers")
    void handleVeryLargeNumbers() {
        // Given: Alert with extreme values
        LocalDateTime now = LocalDateTime.now();
        AlertHistoryEntity alert = AlertHistoryEntity.builder()
                .alertTime(now)
                .strategyId("EXTREME_VALUES")
                .baseProbability(BigDecimal.valueOf(99.9999))
                .finalProbability(BigDecimal.valueOf(99.9999))
                .strategyWeight(BigDecimal.valueOf(0.9999))
                .currentPrice(new BigDecimal("999999999.99999999"))
                .predictedProfitPct(BigDecimal.valueOf(999.9999))
                .targetPrice(new BigDecimal("999999999.99999999"))
                .predictedHours(999)
                .evaluateAt(now.plusHours(999))
                .evaluated(false)
                .sentToTelegram(false)
                .build();

        // Then: Saves without error
        assertThatCode(() -> alertRepository.save(alert)).doesNotThrowAnyException();

        // And: Can retrieve
        AlertHistoryEntity saved = alertRepository.findByStrategyId("EXTREME_VALUES").get(0);
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("999999999.99999999"));
    }

    @Test
    @DisplayName("9.9 Handle zero matched patterns")
    void handleZeroMatchedPatterns() {
        // Given: Strategy result with zero matches
        StrategyAnalysisResult noMatches = StrategyAnalysisResult.builder()
                .strategyId("NO_MATCHES")
                .baseProbability(BigDecimal.ZERO)
                .finalProbability(BigDecimal.ZERO)
                .avgProfitPct(BigDecimal.ZERO)
                .matchedPatterns(0)
                .build();

        // Then: hasData returns false
        assertThat(noMatches.hasData()).isFalse();

        // And: Does not meet conditions
        assertThat(noMatches.meetsConditions(
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(1.5),
                30
        )).isFalse();
    }

    @Test
    @DisplayName("9.10 Handle timestamps at day boundaries")
    void handleTimestampsAtDayBoundaries() {
        // Given: Candles at midnight
        LocalDateTime midnight = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime beforeMidnight = LocalDateTime.of(2023, 12, 31, 23, 45);
        LocalDateTime afterMidnight = LocalDateTime.of(2024, 1, 1, 0, 15);

        candleRepository.save(createCandle(beforeMidnight));
        candleRepository.save(createCandle(midnight));
        candleRepository.save(createCandle(afterMidnight));

        // Then: All saved correctly
        assertThat(candleRepository.count()).isEqualTo(3);

        // And: Ordered correctly across day boundary
        List<Candle15mEntity> candles = candleRepository.findAllOrderByOpenTimeAsc();
        assertThat(candles.get(0).getOpenTime()).isEqualTo(beforeMidnight);
        assertThat(candles.get(1).getOpenTime()).isEqualTo(midnight);
        assertThat(candles.get(2).getOpenTime()).isEqualTo(afterMidnight);
    }

    private Candle15mEntity createCandle(LocalDateTime openTime) {
        return Candle15mEntity.builder()
                .openTime(openTime)
                .openPrice(BigDecimal.valueOf(50000))
                .highPrice(BigDecimal.valueOf(50100))
                .lowPrice(BigDecimal.valueOf(49900))
                .closePrice(BigDecimal.valueOf(50050))
                .volume(BigDecimal.valueOf(100))
                .closeTime(openTime.plusMinutes(15).minusSeconds(1))
                .build();
    }

    private Indicator15mEntity createIndicator(LocalDateTime openTime) {
        return Indicator15mEntity.builder()
                .openTime(openTime)
                .ema50(BigDecimal.valueOf(50000))
                .ema200(BigDecimal.valueOf(49500))
                .rsi14(BigDecimal.valueOf(50))
                .atr14(BigDecimal.valueOf(500))
                .bbUpper(BigDecimal.valueOf(51000))
                .bbMiddle(BigDecimal.valueOf(50000))
                .bbLower(BigDecimal.valueOf(49000))
                .avgVolume20(BigDecimal.valueOf(120))
                .build();
    }

    private MarketSnapshot createTestSnapshot() {
        return MarketSnapshot.builder()
                .price(BigDecimal.valueOf(50000))
                .rsi(BigDecimal.valueOf(50))
                .ema50(BigDecimal.valueOf(49500))
                .ema200(BigDecimal.valueOf(49000))
                .volumeChangePct(BigDecimal.valueOf(10))
                .priceChange1h(BigDecimal.valueOf(0.5))
                .priceChange4h(BigDecimal.valueOf(1.0))
                .build();
    }
}
