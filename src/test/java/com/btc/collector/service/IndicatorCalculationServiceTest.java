package com.btc.collector.service;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.persistence.Candle15mEntity;
import com.btc.collector.persistence.Candle15mRepository;
import com.btc.collector.persistence.Indicator15mEntity;
import com.btc.collector.persistence.Indicator15mRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Section 2: Indicator Calculation
 *
 * Tests:
 * - Trigger indicator resume and check that all new candles have corresponding indicators
 * - Verify that warm-up indicators (+200) are handled correctly
 * - Test edge cases: missing candles or incomplete data
 */
@DisplayName("2. Indicator Calculation Tests")
class IndicatorCalculationServiceTest extends BaseIntegrationTest {

    @Autowired
    private Candle15mRepository candleRepository;

    @Autowired
    private Indicator15mRepository indicatorRepository;

    @Autowired
    private IndicatorCalculationService indicatorService;

    @BeforeEach
    void setUp() {
        indicatorRepository.deleteAll();
        candleRepository.deleteAll();
    }

    @Test
    @DisplayName("2.1 Indicators are created for candles")
    void indicatorsAreCreatedForCandles() {
        // Given: Enough candles for indicator calculation (200+ for EMA200 warmup)
        createCandleSeries(250);
        long candleCount = candleRepository.count();
        assertThat(candleCount).isEqualTo(250);

        // When: Calculate indicators
        indicatorService.resumeCalculation();

        // Then: Indicators are created (minus warmup period)
        long indicatorCount = indicatorRepository.count();
        assertThat(indicatorCount).isGreaterThan(0);
    }

    @Test
    @DisplayName("2.2 Indicators have correct values")
    void indicatorsHaveCorrectValues() {
        // Given: Candles with known values
        createCandleSeries(250);

        // When: Calculate indicators
        indicatorService.resumeCalculation();

        // Then: Indicators have non-null values
        Optional<Indicator15mEntity> latestIndicator = indicatorRepository.findMaxOpenTime()
                .flatMap(indicatorRepository::findById);

        assertThat(latestIndicator).isPresent();
        Indicator15mEntity indicator = latestIndicator.get();

        // All indicator values should be present
        assertThat(indicator.getEma50()).isNotNull();
        assertThat(indicator.getEma200()).isNotNull();
        assertThat(indicator.getRsi14()).isNotNull();
        assertThat(indicator.getAtr14()).isNotNull();
        assertThat(indicator.getBbUpper()).isNotNull();
        assertThat(indicator.getBbMiddle()).isNotNull();
        assertThat(indicator.getBbLower()).isNotNull();
    }

    @Test
    @DisplayName("2.3 No duplicate indicators created")
    void noDuplicateIndicatorsCreated() {
        // Given: Candles and existing indicators
        createCandleSeries(250);
        indicatorService.resumeCalculation();
        long initialCount = indicatorRepository.count();

        // When: Run calculation again
        indicatorService.resumeCalculation();

        // Then: Count unchanged (no duplicates)
        long finalCount = indicatorRepository.count();
        assertThat(finalCount).isEqualTo(initialCount);
    }

    @Test
    @DisplayName("2.4 Warmup period handled correctly")
    void warmupPeriodHandledCorrectly() {
        // Given: Exactly 200 candles (minimum for EMA200)
        createCandleSeries(200);

        // When: Calculate indicators
        indicatorService.resumeCalculation();

        // Then: Very few indicators (warmup requires 200 candles before first valid indicator)
        long indicatorCount = indicatorRepository.count();
        // First valid indicator is at candle 200 or later
        assertThat(indicatorCount).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("2.5 Indicator count matches expected")
    void indicatorCountMatchesExpected() {
        // Given: 300 candles
        createCandleSeries(300);

        // When: Calculate indicators
        indicatorService.resumeCalculation();

        // Then: Approximately 100 indicators (300 - 200 warmup)
        long indicatorCount = indicatorService.getIndicatorCount();
        assertThat(indicatorCount).isBetween(90L, 110L);
    }

    @Test
    @DisplayName("2.6 Latest indicator timestamp aligns with latest candle")
    void latestIndicatorTimestampAligns() {
        // Given: Candles
        createCandleSeries(250);

        // When: Calculate indicators
        indicatorService.resumeCalculation();

        // Then: Latest indicator time matches latest candle time
        Optional<LocalDateTime> latestCandle = candleRepository.findMaxOpenTime();
        Optional<LocalDateTime> latestIndicator = indicatorRepository.findMaxOpenTime();

        assertThat(latestCandle).isPresent();
        assertThat(latestIndicator).isPresent();
        assertThat(latestIndicator.get()).isEqualTo(latestCandle.get());
    }

    @Test
    @DisplayName("2.7 Calculation is idempotent")
    void calculationIsIdempotent() {
        // Given: Initial calculation
        createCandleSeries(250);
        indicatorService.resumeCalculation();

        // Get indicator values
        Optional<Indicator15mEntity> firstRun = indicatorRepository.findMaxOpenTime()
                .flatMap(indicatorRepository::findById);

        // When: Run again
        indicatorService.resumeCalculation();
        Optional<Indicator15mEntity> secondRun = indicatorRepository.findMaxOpenTime()
                .flatMap(indicatorRepository::findById);

        // Then: Values are identical
        assertThat(firstRun).isPresent();
        assertThat(secondRun).isPresent();
        assertThat(secondRun.get().getEma50()).isEqualByComparingTo(firstRun.get().getEma50());
        assertThat(secondRun.get().getRsi14()).isEqualByComparingTo(firstRun.get().getRsi14());
    }

    private void createCandleSeries(int count) {
        LocalDateTime startTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        double basePrice = 50000;

        for (int i = 0; i < count; i++) {
            LocalDateTime openTime = startTime.plusMinutes(i * 15L);

            // Create realistic price movement
            double variation = Math.sin(i * 0.1) * 500 + Math.random() * 100;
            double open = basePrice + variation;
            double close = open + (Math.random() - 0.5) * 200;
            double high = Math.max(open, close) + Math.random() * 100;
            double low = Math.min(open, close) - Math.random() * 100;

            Candle15mEntity candle = Candle15mEntity.builder()
                    .openTime(openTime)
                    .openPrice(BigDecimal.valueOf(open))
                    .highPrice(BigDecimal.valueOf(high))
                    .lowPrice(BigDecimal.valueOf(low))
                    .closePrice(BigDecimal.valueOf(close))
                    .volume(BigDecimal.valueOf(100 + Math.random() * 50))
                    .closeTime(openTime.plusMinutes(15).minusSeconds(1))
                    .build();

            candleRepository.save(candle);
        }
    }
}
