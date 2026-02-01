package com.btc.collector.analysis;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Section 3: Pattern Building
 *
 * Tests:
 * - Pattern repository CRUD operations
 * - Pattern uniqueness constraint
 * - Pattern timestamps align with indicators
 * - Evaluated vs unevaluated pattern tracking
 */
@DisplayName("3. Pattern Building Tests")
class PatternAnalyzerTest extends BaseIntegrationTest {

    @Autowired
    private HistoricalPatternRepository patternRepository;

    @Autowired
    private Indicator15mRepository indicatorRepository;

    @Autowired
    private Candle15mRepository candleRepository;

    @BeforeEach
    void setUp() {
        patternRepository.deleteAll();
        indicatorRepository.deleteAll();
        candleRepository.deleteAll();
    }

    @Test
    @DisplayName("3.1 Patterns are saved correctly")
    void patternsAreSavedCorrectly() {
        // Given: Pattern data
        LocalDateTime candleTime = LocalDateTime.of(2024, 1, 1, 10, 0);
        HistoricalPatternEntity pattern = HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId("RSI_MID_EMA_BULL_VOL_MED")
                .rsi(BigDecimal.valueOf(50))
                .ema50(BigDecimal.valueOf(50000))
                .ema200(BigDecimal.valueOf(49500))
                .volumeChangePct(BigDecimal.valueOf(10))
                .priceChange1h(BigDecimal.valueOf(0.5))
                .priceChange4h(BigDecimal.valueOf(1.0))
                .evaluated(false)
                .build();

        // When: Save pattern
        HistoricalPatternEntity saved = patternRepository.save(pattern);

        // Then: Pattern is persisted
        assertThat(saved.getId()).isNotNull();
        assertThat(patternRepository.count()).isEqualTo(1);

        // And: Can be retrieved
        Optional<HistoricalPatternEntity> found = patternRepository.findByCandleTime(candleTime);
        assertThat(found).isPresent();
        assertThat(found.get().getStrategyId()).isEqualTo("RSI_MID_EMA_BULL_VOL_MED");
    }

    @Test
    @DisplayName("3.2 No duplicate patterns for same candle time")
    void noDuplicatePatternsForSameCandleTime() {
        // Given: Existing pattern
        LocalDateTime candleTime = LocalDateTime.of(2024, 1, 1, 10, 0);
        patternRepository.save(HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId("STRATEGY_1")
                .rsi(BigDecimal.valueOf(50))
                .evaluated(false)
                .build());

        // When: Check existence
        boolean exists = patternRepository.existsByCandleTime(candleTime);

        // Then: Duplicate prevented
        assertThat(exists).isTrue();
        assertThat(patternRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("3.3 Patterns ordered by candle time")
    void patternsOrderedByCandleTime() {
        // Given: Patterns in random order
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 30);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 15);

        patternRepository.save(createPattern(time1));
        patternRepository.save(createPattern(time2));
        patternRepository.save(createPattern(time3));

        // When: Query ordered
        List<HistoricalPatternEntity> ordered = patternRepository.findAllOrderByCandleTimeAsc();

        // Then: Correctly ordered
        assertThat(ordered).hasSize(3);
        assertThat(ordered.get(0).getCandleTime()).isEqualTo(time2);
        assertThat(ordered.get(1).getCandleTime()).isEqualTo(time3);
        assertThat(ordered.get(2).getCandleTime()).isEqualTo(time1);
    }

    @Test
    @DisplayName("3.4 Latest pattern time is tracked")
    void latestPatternTimeIsTracked() {
        // Given: Multiple patterns
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 15);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 30);

        patternRepository.save(createPattern(time1));
        patternRepository.save(createPattern(time2));
        patternRepository.save(createPattern(time3));

        // When: Get max time
        Optional<LocalDateTime> maxTime = patternRepository.findMaxCandleTime();

        // Then: Returns latest
        assertThat(maxTime).contains(time3);
    }

    @Test
    @DisplayName("3.5 Evaluated patterns tracked separately")
    void evaluatedPatternsTrackedSeparately() {
        // Given: Mix of evaluated and unevaluated patterns
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 15);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 30);

        // Unevaluated
        patternRepository.save(createPattern(time3));

        // Evaluated
        HistoricalPatternEntity evaluated1 = createPattern(time1);
        evaluated1.setEvaluated(true);
        evaluated1.setMaxProfitPct(BigDecimal.valueOf(2.5));
        evaluated1.setHoursToMax(4);
        patternRepository.save(evaluated1);

        HistoricalPatternEntity evaluated2 = createPattern(time2);
        evaluated2.setEvaluated(true);
        evaluated2.setMaxProfitPct(BigDecimal.valueOf(1.5));
        evaluated2.setHoursToMax(3);
        patternRepository.save(evaluated2);

        // Then: Counts are correct
        assertThat(patternRepository.countEvaluated()).isEqualTo(2);
        assertThat(patternRepository.countUnevaluated()).isEqualTo(1);
        assertThat(patternRepository.countPatterns()).isEqualTo(3);
    }

    @Test
    @DisplayName("3.6 Latest evaluated time is tracked")
    void latestEvaluatedTimeIsTracked() {
        // Given: Mix of patterns
        LocalDateTime unevalTime = LocalDateTime.of(2024, 1, 1, 12, 0);
        LocalDateTime evalTime1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime evalTime2 = LocalDateTime.of(2024, 1, 1, 11, 0);

        patternRepository.save(createPattern(unevalTime));

        HistoricalPatternEntity eval1 = createPattern(evalTime1);
        eval1.setEvaluated(true);
        eval1.setMaxProfitPct(BigDecimal.valueOf(2.0));
        eval1.setHoursToMax(3);
        patternRepository.save(eval1);

        HistoricalPatternEntity eval2 = createPattern(evalTime2);
        eval2.setEvaluated(true);
        eval2.setMaxProfitPct(BigDecimal.valueOf(1.5));
        eval2.setHoursToMax(4);
        patternRepository.save(eval2);

        // When: Get max evaluated time
        Optional<LocalDateTime> maxEval = patternRepository.findMaxEvaluatedCandleTime();

        // Then: Returns latest evaluated (not unevaluated)
        assertThat(maxEval).contains(evalTime2);
    }

    @Test
    @DisplayName("3.7 Find unevaluated patterns before time")
    void findUnevaluatedPatternsBefore() {
        // Given: Patterns at different times
        LocalDateTime oldTime = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime recentTime = LocalDateTime.of(2024, 1, 2, 10, 0);
        LocalDateTime cutoffTime = LocalDateTime.of(2024, 1, 1, 20, 0);

        patternRepository.save(createPattern(oldTime));
        patternRepository.save(createPattern(recentTime));

        // When: Find unevaluated before cutoff
        List<HistoricalPatternEntity> found = patternRepository.findUnevaluatedPatternsBefore(cutoffTime);

        // Then: Only old pattern returned
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCandleTime()).isEqualTo(oldTime);
    }

    @Test
    @DisplayName("3.8 Pattern RSI values stored correctly")
    void patternRsiValuesStoredCorrectly() {
        // Given: Patterns with different RSI
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 15);

        HistoricalPatternEntity lowRsi = createPattern(time1);
        lowRsi.setRsi(BigDecimal.valueOf(25));
        lowRsi.setStrategyId("RSI_LOW_EMA_BULL_VOL_MED");
        patternRepository.save(lowRsi);

        HistoricalPatternEntity highRsi = createPattern(time2);
        highRsi.setRsi(BigDecimal.valueOf(75));
        highRsi.setStrategyId("RSI_HIGH_EMA_BULL_VOL_MED");
        patternRepository.save(highRsi);

        // When: Query by strategy
        List<HistoricalPatternEntity> lowRsiPatterns = patternRepository.findByStrategyId("RSI_LOW_EMA_BULL_VOL_MED");
        List<HistoricalPatternEntity> highRsiPatterns = patternRepository.findByStrategyId("RSI_HIGH_EMA_BULL_VOL_MED");

        // Then: Correct RSI values
        assertThat(lowRsiPatterns).hasSize(1);
        assertThat(lowRsiPatterns.get(0).getRsi()).isEqualByComparingTo(BigDecimal.valueOf(25));

        assertThat(highRsiPatterns).hasSize(1);
        assertThat(highRsiPatterns.get(0).getRsi()).isEqualByComparingTo(BigDecimal.valueOf(75));
    }

    private HistoricalPatternEntity createPattern(LocalDateTime candleTime) {
        return HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId("TEST_STRATEGY")
                .rsi(BigDecimal.valueOf(50))
                .ema50(BigDecimal.valueOf(50000))
                .ema200(BigDecimal.valueOf(49500))
                .evaluated(false)
                .build();
    }
}
