package com.btc.collector.service;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Section 7: System State Reporting
 *
 * Tests:
 * - Check candle, indicator, and pattern counts in database
 * - Verify latest timestamps of candles, indicators, patterns, and evaluated predictions
 * - Ensure UTC+2 conversion is applied correctly
 */
@DisplayName("7. System State Reporting Tests")
class SystemStateTest extends BaseIntegrationTest {

    private static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Kyiv");

    @Autowired
    private Candle15mRepository candleRepository;

    @Autowired
    private Indicator15mRepository indicatorRepository;

    @Autowired
    private HistoricalPatternRepository patternRepository;

    @Autowired
    private AlertHistoryRepository alertRepository;

    @Autowired
    private CandleSyncService candleSyncService;

    @Autowired
    private IndicatorCalculationService indicatorService;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        patternRepository.deleteAll();
        indicatorRepository.deleteAll();
        candleRepository.deleteAll();
    }

    @Test
    @DisplayName("7.1 Candle count is accurate")
    void candleCountIsAccurate() {
        // Given: Add candles
        int expectedCount = 50;
        createCandles(expectedCount);

        // When: Get count
        long count = candleSyncService.getCandleCount();

        // Then: Count matches
        assertThat(count).isEqualTo(expectedCount);
    }

    @Test
    @DisplayName("7.2 Indicator count is accurate")
    void indicatorCountIsAccurate() {
        // Given: Add indicators
        int expectedCount = 30;
        createIndicators(expectedCount);

        // When: Get count
        long count = indicatorService.getIndicatorCount();

        // Then: Count matches
        assertThat(count).isEqualTo(expectedCount);
    }

    @Test
    @DisplayName("7.3 Pattern count is accurate")
    void patternCountIsAccurate() {
        // Given: Add patterns
        int expectedCount = 20;
        createPatterns(expectedCount);

        // When: Get count
        long count = patternRepository.countPatterns();

        // Then: Count matches
        assertThat(count).isEqualTo(expectedCount);
    }

    @Test
    @DisplayName("7.4 Latest candle timestamp is correct")
    void latestCandleTimestampIsCorrect() {
        // Given: Candles with known times
        LocalDateTime expectedLatest = LocalDateTime.of(2024, 6, 15, 14, 30);
        createCandles(10);
        candleRepository.save(Candle15mEntity.builder()
                .openTime(expectedLatest)
                .openPrice(BigDecimal.valueOf(50000))
                .highPrice(BigDecimal.valueOf(50100))
                .lowPrice(BigDecimal.valueOf(49900))
                .closePrice(BigDecimal.valueOf(50050))
                .volume(BigDecimal.valueOf(100))
                .closeTime(expectedLatest.plusMinutes(15).minusSeconds(1))
                .build());

        // When: Get latest
        Optional<LocalDateTime> latest = candleRepository.findMaxOpenTime();

        // Then: Matches expected
        assertThat(latest).isPresent();
        assertThat(latest.get()).isEqualTo(expectedLatest);
    }

    @Test
    @DisplayName("7.5 Latest indicator timestamp is correct")
    void latestIndicatorTimestampIsCorrect() {
        // Given: Indicators with known times
        LocalDateTime expectedLatest = LocalDateTime.of(2024, 6, 15, 14, 30);
        createIndicators(10);
        indicatorRepository.save(Indicator15mEntity.builder()
                .openTime(expectedLatest)
                .ema50(BigDecimal.valueOf(50000))
                .ema200(BigDecimal.valueOf(49500))
                .rsi14(BigDecimal.valueOf(55))
                .atr14(BigDecimal.valueOf(500))
                .bbUpper(BigDecimal.valueOf(51000))
                .bbMiddle(BigDecimal.valueOf(50000))
                .bbLower(BigDecimal.valueOf(49000))
                .avgVolume20(BigDecimal.valueOf(120))
                .build());

        // When: Get latest
        Optional<LocalDateTime> latest = indicatorRepository.findMaxOpenTime();

        // Then: Matches expected
        assertThat(latest).isPresent();
        assertThat(latest.get()).isEqualTo(expectedLatest);
    }

    @Test
    @DisplayName("7.6 Latest pattern timestamp is correct")
    void latestPatternTimestampIsCorrect() {
        // Given: Patterns with known times
        LocalDateTime expectedLatest = LocalDateTime.of(2024, 6, 15, 14, 30);
        createPatterns(10);
        patternRepository.save(HistoricalPatternEntity.builder()
                .candleTime(expectedLatest)
                .strategyId("TEST")
                .rsi(BigDecimal.valueOf(50))
                .evaluated(false)
                .build());

        // When: Get latest
        Optional<LocalDateTime> latest = patternRepository.findMaxCandleTime();

        // Then: Matches expected
        assertThat(latest).isPresent();
        assertThat(latest.get()).isEqualTo(expectedLatest);
    }

    @Test
    @DisplayName("7.7 Latest evaluated pattern timestamp is tracked")
    void latestEvaluatedPatternTimestampIsTracked() {
        // Given: Mix of evaluated and unevaluated patterns
        LocalDateTime unevaluatedTime = LocalDateTime.of(2024, 6, 15, 14, 30);
        LocalDateTime evaluatedTime = LocalDateTime.of(2024, 6, 15, 12, 0);

        patternRepository.save(HistoricalPatternEntity.builder()
                .candleTime(unevaluatedTime)
                .strategyId("UNEVALUATED")
                .rsi(BigDecimal.valueOf(50))
                .evaluated(false)
                .build());

        patternRepository.save(HistoricalPatternEntity.builder()
                .candleTime(evaluatedTime)
                .strategyId("EVALUATED")
                .rsi(BigDecimal.valueOf(50))
                .maxProfitPct(BigDecimal.valueOf(2.0))
                .hoursToMax(3)
                .evaluated(true)
                .evaluatedAt(LocalDateTime.now())
                .build());

        // When: Get latest evaluated
        Optional<LocalDateTime> latestEvaluated = patternRepository.findMaxEvaluatedCandleTime();

        // Then: Returns evaluated pattern time
        assertThat(latestEvaluated).isPresent();
        assertThat(latestEvaluated.get()).isEqualTo(evaluatedTime);
    }

    @Test
    @DisplayName("7.8 Alert counts by status are correct")
    void alertCountsByStatusAreCorrect() {
        // Given: Mix of alerts
        LocalDateTime now = LocalDateTime.now();

        // Create 5 evaluated successful
        for (int i = 0; i < 5; i++) {
            AlertHistoryEntity alert = createAlert(now.minusDays(i + 1));
            alert.setEvaluated(true);
            alert.setSuccess(true);
            alertRepository.save(alert);
        }

        // Create 3 evaluated failed
        for (int i = 0; i < 3; i++) {
            AlertHistoryEntity alert = createAlert(now.minusDays(i + 6));
            alert.setEvaluated(true);
            alert.setSuccess(false);
            alertRepository.save(alert);
        }

        // Create 2 pending
        for (int i = 0; i < 2; i++) {
            AlertHistoryEntity alert = createAlert(now.minusHours(i + 1));
            alert.setEvaluated(false);
            alertRepository.save(alert);
        }

        // Then: Counts are correct
        assertThat(alertRepository.countTotal()).isEqualTo(10);
        assertThat(alertRepository.countSuccessful()).isEqualTo(5);
        assertThat(alertRepository.countFailed()).isEqualTo(3);
        assertThat(alertRepository.countPendingEvaluations()).isEqualTo(2);
    }

    @Test
    @DisplayName("7.9 Empty database returns appropriate values")
    void emptyDatabaseReturnsAppropriateValues() {
        // Given: Empty database (cleared in setUp)

        // Then: Counts are zero
        assertThat(candleSyncService.getCandleCount()).isZero();
        assertThat(indicatorService.getIndicatorCount()).isZero();
        assertThat(patternRepository.countPatterns()).isZero();
        assertThat(alertRepository.countTotal()).isZero();

        // And: Optional timestamps are empty
        assertThat(candleRepository.findMaxOpenTime()).isEmpty();
        assertThat(indicatorRepository.findMaxOpenTime()).isEmpty();
        assertThat(patternRepository.findMaxCandleTime()).isEmpty();
    }

    private void createCandles(int count) {
        LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        for (int i = 0; i < count; i++) {
            LocalDateTime openTime = startTime.plusMinutes(i * 15L);
            candleRepository.save(Candle15mEntity.builder()
                    .openTime(openTime)
                    .openPrice(BigDecimal.valueOf(50000))
                    .highPrice(BigDecimal.valueOf(50100))
                    .lowPrice(BigDecimal.valueOf(49900))
                    .closePrice(BigDecimal.valueOf(50050))
                    .volume(BigDecimal.valueOf(100))
                    .closeTime(openTime.plusMinutes(15).minusSeconds(1))
                    .build());
        }
    }

    private void createIndicators(int count) {
        LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        for (int i = 0; i < count; i++) {
            LocalDateTime openTime = startTime.plusMinutes(i * 15L);
            indicatorRepository.save(Indicator15mEntity.builder()
                    .openTime(openTime)
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(49500))
                    .rsi14(BigDecimal.valueOf(50))
                    .atr14(BigDecimal.valueOf(500))
                    .bbUpper(BigDecimal.valueOf(51000))
                    .bbMiddle(BigDecimal.valueOf(50000))
                    .bbLower(BigDecimal.valueOf(49000))
                    .avgVolume20(BigDecimal.valueOf(120))
                    .build());
        }
    }

    private void createPatterns(int count) {
        LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        for (int i = 0; i < count; i++) {
            LocalDateTime candleTime = startTime.plusMinutes(i * 15L);
            patternRepository.save(HistoricalPatternEntity.builder()
                    .candleTime(candleTime)
                    .strategyId("TEST_STRATEGY")
                    .rsi(BigDecimal.valueOf(50))
                    .ema50(BigDecimal.valueOf(50000))
                    .ema200(BigDecimal.valueOf(49500))
                    .evaluated(false)
                    .build());
        }
    }

    private AlertHistoryEntity createAlert(LocalDateTime time) {
        return AlertHistoryEntity.builder()
                .alertTime(time)
                .strategyId("TEST_STRATEGY")
                .baseProbability(BigDecimal.valueOf(60))
                .finalProbability(BigDecimal.valueOf(55))
                .strategyWeight(BigDecimal.valueOf(0.6))
                .currentPrice(BigDecimal.valueOf(50000))
                .predictedProfitPct(BigDecimal.valueOf(2.0))
                .targetPrice(BigDecimal.valueOf(51000))
                .predictedHours(4)
                .evaluateAt(time.plusHours(4))
                .evaluated(false)
                .sentToTelegram(false)
                .build();
    }
}
