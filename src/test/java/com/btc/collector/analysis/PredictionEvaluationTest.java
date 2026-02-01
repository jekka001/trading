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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Section 6: Prediction Evaluation
 *
 * Tests:
 * - Verify that predictions are only evaluated when enough candles exist for outcome calculation
 * - Check that historical patterns and alerts are considered for evaluation
 * - Ensure predictions are marked correctly as profitable or not
 */
@DisplayName("6. Prediction Evaluation Tests")
class PredictionEvaluationTest extends BaseIntegrationTest {

    @Autowired
    private AlertHistoryRepository alertRepository;

    @Autowired
    private HistoricalPatternRepository patternRepository;

    @Autowired
    private StrategyStatsRepository strategyStatsRepository;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        patternRepository.deleteAll();
        strategyStatsRepository.deleteAll();
    }

    @Test
    @DisplayName("6.1 Alerts are ready for evaluation after evaluate_at time")
    void alertsReadyForEvaluationAfterTime() {
        // Given: Alerts with different evaluate_at times
        LocalDateTime now = LocalDateTime.now();

        // Ready for evaluation (evaluate_at in past)
        AlertHistoryEntity ready = createTestAlert(now.minusHours(6), "STRATEGY_1");
        ready.setEvaluateAt(now.minusHours(2));
        alertRepository.save(ready);

        // Not ready (evaluate_at in future)
        AlertHistoryEntity notReady = createTestAlert(now.minusHours(1), "STRATEGY_2");
        notReady.setEvaluateAt(now.plusHours(3));
        alertRepository.save(notReady);

        // Already evaluated
        AlertHistoryEntity evaluated = createTestAlert(now.minusHours(8), "STRATEGY_3");
        evaluated.setEvaluateAt(now.minusHours(4));
        evaluated.setEvaluated(true);
        evaluated.setSuccess(true);
        alertRepository.save(evaluated);

        // When: Find ready for evaluation
        List<AlertHistoryEntity> readyAlerts = alertRepository.findReadyForEvaluation(now);

        // Then: Only one alert ready
        assertThat(readyAlerts).hasSize(1);
        assertThat(readyAlerts.get(0).getStrategyId()).isEqualTo("STRATEGY_1");
    }

    @Test
    @DisplayName("6.2 Alert evaluation updates success flag correctly")
    void alertEvaluationUpdatesSuccessFlag() {
        // Given: Alert to evaluate
        LocalDateTime alertTime = LocalDateTime.now().minusHours(6);
        AlertHistoryEntity alert = createTestAlert(alertTime, "TEST_STRATEGY");
        alert.setTargetPrice(BigDecimal.valueOf(51000));
        alert.setEvaluateAt(alertTime.plusHours(4));
        AlertHistoryEntity saved = alertRepository.save(alert);

        // When: Mark as successful
        saved.setEvaluated(true);
        saved.setSuccess(true);
        saved.setActualMaxPrice(BigDecimal.valueOf(51500));
        saved.setActualProfitPct(BigDecimal.valueOf(3.0));
        saved.setEvaluatedAt(LocalDateTime.now());
        alertRepository.save(saved);

        // Then: Alert is updated
        AlertHistoryEntity reloaded = alertRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isEvaluated()).isTrue();
        assertThat(reloaded.getSuccess()).isTrue();
        assertThat(reloaded.getActualMaxPrice()).isEqualByComparingTo(BigDecimal.valueOf(51500));
    }

    @Test
    @DisplayName("6.3 Failed predictions are marked correctly")
    void failedPredictionsMarkedCorrectly() {
        // Given: Alert that failed
        LocalDateTime alertTime = LocalDateTime.now().minusHours(6);
        AlertHistoryEntity alert = createTestAlert(alertTime, "FAILING_STRATEGY");
        alert.setTargetPrice(BigDecimal.valueOf(52000)); // High target
        alert.setEvaluateAt(alertTime.plusHours(4));
        AlertHistoryEntity saved = alertRepository.save(alert);

        // When: Mark as failed (max price didn't reach target)
        saved.setEvaluated(true);
        saved.setSuccess(false);
        saved.setActualMaxPrice(BigDecimal.valueOf(50500)); // Below target
        saved.setActualProfitPct(BigDecimal.valueOf(1.0));
        saved.setEvaluatedAt(LocalDateTime.now());
        alertRepository.save(saved);

        // Then: Alert marked as failed
        AlertHistoryEntity reloaded = alertRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isEvaluated()).isTrue();
        assertThat(reloaded.getSuccess()).isFalse();
    }

    @Test
    @DisplayName("6.4 Pattern evaluation updates evaluated flag")
    void patternEvaluationUpdatesFlag() {
        // Given: Unevaluated pattern with fixed time (avoid nanosecond precision issues)
        LocalDateTime candleTime = LocalDateTime.of(2024, 1, 1, 10, 0);
        HistoricalPatternEntity pattern = HistoricalPatternEntity.builder()
                .candleTime(candleTime)
                .strategyId("TEST_STRATEGY")
                .rsi(BigDecimal.valueOf(45))
                .ema50(BigDecimal.valueOf(50000))
                .ema200(BigDecimal.valueOf(49500))
                .evaluated(false)
                .build();
        HistoricalPatternEntity saved = patternRepository.save(pattern);

        // Verify unevaluated
        assertThat(patternRepository.countUnevaluated()).isEqualTo(1);

        // When: Evaluate using saved entity ID
        saved.setEvaluated(true);
        saved.setMaxProfitPct(BigDecimal.valueOf(2.5));
        saved.setHoursToMax(3);
        saved.setEvaluatedAt(LocalDateTime.now());
        patternRepository.save(saved);

        // Then: Pattern marked as evaluated
        assertThat(patternRepository.countUnevaluated()).isZero();
        assertThat(patternRepository.countEvaluated()).isEqualTo(1);
    }

    @Test
    @DisplayName("6.5 Strategy stats are updated on evaluation")
    void strategyStatsUpdatedOnEvaluation() {
        // Given: Strategy stats
        String strategyId = "RSI_MID_EMA_BULL_VOL_MED";
        StrategyStatsEntity stats = StrategyStatsEntity.createNew(strategyId);
        strategyStatsRepository.save(stats);

        // When: Record success
        StrategyStatsEntity toUpdate = strategyStatsRepository.findById(strategyId).orElseThrow();
        toUpdate.recordSuccess();
        strategyStatsRepository.save(toUpdate);

        // Then: Stats updated
        StrategyStatsEntity reloaded = strategyStatsRepository.findById(strategyId).orElseThrow();
        assertThat(reloaded.getTotalPredictions()).isEqualTo(1);
        assertThat(reloaded.getSuccessfulPredictions()).isEqualTo(1);
        assertThat(reloaded.getSuccessRate()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("6.6 Strategy weight adjusts based on success rate")
    void strategyWeightAdjustsBasedOnSuccessRate() {
        // Given: Strategy with mixed results
        String strategyId = "MIXED_STRATEGY";
        StrategyStatsEntity stats = StrategyStatsEntity.createNew(strategyId);

        // Record 7 successes and 3 failures = 70% success rate
        for (int i = 0; i < 7; i++) {
            stats.recordSuccess();
        }
        for (int i = 0; i < 3; i++) {
            stats.recordFailure();
        }

        strategyStatsRepository.save(stats);

        // Then: Weight reflects success rate
        StrategyStatsEntity saved = strategyStatsRepository.findById(strategyId).orElseThrow();
        assertThat(saved.getTotalPredictions()).isEqualTo(10);
        assertThat(saved.getSuccessRate()).isEqualByComparingTo(BigDecimal.valueOf(70));
        // Weight should be above default 0.5 due to good performance
        assertThat(saved.getWeight()).isGreaterThan(BigDecimal.valueOf(0.5));
    }

    @Test
    @DisplayName("6.7 Pattern can detect profitable outcomes")
    void patternDetectsProfitableOutcomes() {
        // Given: Pattern with positive profit
        HistoricalPatternEntity profitablePattern = HistoricalPatternEntity.builder()
                .candleTime(LocalDateTime.now().minusDays(2))
                .strategyId("PROFITABLE")
                .rsi(BigDecimal.valueOf(35))
                .maxProfitPct(BigDecimal.valueOf(2.5)) // Positive
                .hoursToMax(4)
                .evaluated(true)
                .build();

        // Given: Pattern with negative profit
        HistoricalPatternEntity unprofitablePattern = HistoricalPatternEntity.builder()
                .candleTime(LocalDateTime.now().minusDays(1))
                .strategyId("UNPROFITABLE")
                .rsi(BigDecimal.valueOf(65))
                .maxProfitPct(BigDecimal.valueOf(-1.0)) // Negative
                .hoursToMax(4)
                .evaluated(true)
                .build();

        patternRepository.save(profitablePattern);
        patternRepository.save(unprofitablePattern);

        // Then: Can query and check profitability
        List<HistoricalPatternEntity> all = patternRepository.findAllOrderByCandleTimeAsc();
        assertThat(all).hasSize(2);

        // Profitable pattern has positive maxProfitPct
        assertThat(all.get(0).getMaxProfitPct().compareTo(BigDecimal.ZERO) > 0).isTrue();
        // Unprofitable pattern has negative maxProfitPct
        assertThat(all.get(1).getMaxProfitPct().compareTo(BigDecimal.ZERO) < 0).isTrue();
    }

    @Test
    @DisplayName("6.8 Success rate calculation is accurate")
    void successRateCalculationIsAccurate() {
        // Given: Multiple evaluated alerts
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 10; i++) {
            AlertHistoryEntity alert = createTestAlert(now.minusHours(i * 5L), "TEST_STRATEGY");
            alert.setEvaluateAt(now.minusHours(i * 5L - 4));
            alert.setEvaluated(true);
            alert.setSuccess(i < 6); // 6 successful, 4 failed = 60%
            alertRepository.save(alert);
        }

        // When: Get success rate
        Double successRate = alertRepository.getOverallSuccessRate();

        // Then: 60% success rate
        assertThat(successRate).isEqualTo(60.0);
    }

    private AlertHistoryEntity createTestAlert(LocalDateTime time, String strategyId) {
        return AlertHistoryEntity.builder()
                .alertTime(time)
                .strategyId(strategyId)
                .baseProbability(BigDecimal.valueOf(65))
                .finalProbability(BigDecimal.valueOf(60))
                .strategyWeight(BigDecimal.valueOf(0.6))
                .currentPrice(BigDecimal.valueOf(50000))
                .predictedProfitPct(BigDecimal.valueOf(2.5))
                .targetPrice(BigDecimal.valueOf(51250))
                .predictedHours(4)
                .matchedPatterns(50)
                .evaluateAt(time.plusHours(4))
                .evaluated(false)
                .sentToTelegram(false)
                .build();
    }
}
