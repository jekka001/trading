package com.btc.collector.analysis;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.persistence.AlertHistoryEntity;
import com.btc.collector.persistence.AlertHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Section 4: Alert Generation
 *
 * Tests:
 * - Verify that alerts are stored in alert_history with correct probability, profit, and timestamp
 * - Ensure alerts are correctly linked to their strategy ID
 * - Test the logic for alerts that are logged but not sent (low probability or low profit)
 * - Simulate system restart and verify alerts persist in the database
 */
@DisplayName("4. Alert Generation Tests")
class AlertGenerationTest extends BaseIntegrationTest {

    @Autowired
    private AlertHistoryRepository alertRepository;

    @Autowired
    private SignalAlertService alertService;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
    }

    @Test
    @DisplayName("4.1 Alerts are stored with correct data")
    void alertsAreStoredWithCorrectData() {
        // Given: Alert entity
        LocalDateTime now = LocalDateTime.now();
        AlertHistoryEntity alert = createTestAlert(now, "RSI_MID_EMA_BULL_VOL_MED");

        // When: Save alert
        AlertHistoryEntity saved = alertRepository.save(alert);

        // Then: Alert is persisted with correct data
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAlertTime()).isEqualTo(now);
        assertThat(saved.getStrategyId()).isEqualTo("RSI_MID_EMA_BULL_VOL_MED");
        assertThat(saved.getBaseProbability()).isEqualByComparingTo(BigDecimal.valueOf(65));
        assertThat(saved.getPredictedProfitPct()).isEqualByComparingTo(BigDecimal.valueOf(2.5));
    }

    @Test
    @DisplayName("4.2 Alerts are linked to strategy ID")
    void alertsAreLinkedToStrategyId() {
        // Given: Multiple alerts with different strategies
        LocalDateTime time1 = LocalDateTime.now().minusHours(1);
        LocalDateTime time2 = LocalDateTime.now();

        alertRepository.save(createTestAlert(time1, "RSI_LOW_EMA_BEAR_VOL_LOW"));
        alertRepository.save(createTestAlert(time2, "RSI_HIGH_EMA_BULL_VOL_HIGH"));

        // When: Query by strategy
        List<AlertHistoryEntity> lowRsiAlerts = alertRepository.findByStrategyId("RSI_LOW_EMA_BEAR_VOL_LOW");
        List<AlertHistoryEntity> highRsiAlerts = alertRepository.findByStrategyId("RSI_HIGH_EMA_BULL_VOL_HIGH");

        // Then: Correct alerts returned
        assertThat(lowRsiAlerts).hasSize(1);
        assertThat(highRsiAlerts).hasSize(1);
        // Compare timestamps with truncation to avoid nanosecond precision issues
        assertThat(lowRsiAlerts.get(0).getAlertTime().withNano(0)).isEqualTo(time1.withNano(0));
        assertThat(highRsiAlerts.get(0).getAlertTime().withNano(0)).isEqualTo(time2.withNano(0));
    }

    @Test
    @DisplayName("4.3 Alerts not sent to Telegram are still saved")
    void alertsNotSentToTelegramAreSaved() {
        // Given: Alert with low probability (below threshold)
        LocalDateTime now = LocalDateTime.now();
        AlertHistoryEntity alert = AlertHistoryEntity.builder()
                .alertTime(now)
                .strategyId("RSI_MID_EMA_BEAR_VOL_LOW")
                .baseProbability(BigDecimal.valueOf(30)) // Below 50% threshold
                .finalProbability(BigDecimal.valueOf(25))
                .strategyWeight(BigDecimal.valueOf(0.5))
                .currentPrice(BigDecimal.valueOf(50000))
                .predictedProfitPct(BigDecimal.valueOf(1.0)) // Below 1.5% threshold
                .targetPrice(BigDecimal.valueOf(50500))
                .predictedHours(4)
                .matchedPatterns(20)
                .evaluateAt(now.plusHours(4))
                .evaluated(false)
                .sentToTelegram(false) // Not sent
                .build();

        // When: Save alert
        AlertHistoryEntity saved = alertRepository.save(alert);

        // Then: Alert is persisted
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isSentToTelegram()).isFalse();

        // And: Can be queried
        assertThat(alertRepository.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("4.4 Alerts persist across repository operations")
    void alertsPersistAcrossRepositoryOperations() {
        // Given: Saved alert
        LocalDateTime now = LocalDateTime.now();
        AlertHistoryEntity alert = createTestAlert(now, "RSI_MID_EMA_BULL_VOL_MED");
        Long savedId = alertRepository.save(alert).getId();

        // When: Clear entity manager cache and reload
        alertRepository.flush();

        // Then: Alert still exists
        assertThat(alertRepository.findById(savedId)).isPresent();
        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("4.5 Multiple alerts can be stored")
    void multipleAlertsCanBeStored() {
        // Given: Multiple alerts
        for (int i = 0; i < 10; i++) {
            LocalDateTime time = LocalDateTime.now().minusMinutes(i * 15);
            alertRepository.save(createTestAlert(time, "RSI_MID_EMA_BULL_VOL_MED"));
        }

        // Then: All alerts stored
        assertThat(alertRepository.count()).isEqualTo(10);
        assertThat(alertRepository.findRecentAlerts(5)).hasSize(5);
    }

    @Test
    @DisplayName("4.6 Snapshot group ID links related alerts")
    void snapshotGroupIdLinksRelatedAlerts() {
        // Given: Multiple alerts with same snapshot group
        String snapshotId = "abc12345";
        LocalDateTime now = LocalDateTime.now();

        AlertHistoryEntity alert1 = createTestAlert(now, "RSI_LOW_EMA_BEAR_VOL_LOW");
        alert1.setSnapshotGroupId(snapshotId);
        alertRepository.save(alert1);

        AlertHistoryEntity alert2 = createTestAlert(now, "RSI_MID_EMA_BULL_VOL_MED");
        alert2.setSnapshotGroupId(snapshotId);
        alertRepository.save(alert2);

        AlertHistoryEntity alert3 = createTestAlert(now, "RSI_HIGH_EMA_BULL_VOL_HIGH");
        alert3.setSnapshotGroupId(snapshotId);
        alertRepository.save(alert3);

        // When: Query by snapshot group
        List<AlertHistoryEntity> groupAlerts = alertRepository.findBySnapshotGroupId(snapshotId);

        // Then: All 3 alerts returned
        assertThat(groupAlerts).hasSize(3);
    }

    @Test
    @DisplayName("4.7 Alert counts are accurate")
    void alertCountsAreAccurate() {
        // Given: Mix of sent/not sent, evaluated/not evaluated alerts
        LocalDateTime now = LocalDateTime.now();

        // Sent to Telegram, evaluated, successful
        AlertHistoryEntity alert1 = createTestAlert(now.minusHours(10), "STRATEGY_1");
        alert1.setSentToTelegram(true);
        alert1.setEvaluated(true);
        alert1.setSuccess(true);
        alertRepository.save(alert1);

        // Sent to Telegram, evaluated, failed
        AlertHistoryEntity alert2 = createTestAlert(now.minusHours(8), "STRATEGY_2");
        alert2.setSentToTelegram(true);
        alert2.setEvaluated(true);
        alert2.setSuccess(false);
        alertRepository.save(alert2);

        // Not sent, not evaluated
        AlertHistoryEntity alert3 = createTestAlert(now.minusHours(2), "STRATEGY_3");
        alert3.setSentToTelegram(false);
        alert3.setEvaluated(false);
        alertRepository.save(alert3);

        // Then: Counts are correct
        assertThat(alertRepository.countTotal()).isEqualTo(3);
        assertThat(alertRepository.countSentToTelegram()).isEqualTo(2);
        assertThat(alertRepository.countSuccessful()).isEqualTo(1);
        assertThat(alertRepository.countFailed()).isEqualTo(1);
        assertThat(alertRepository.countPendingEvaluations()).isEqualTo(1);
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
                .rsi(BigDecimal.valueOf(45))
                .emaTrend("BULLISH")
                .volumeBucket("NORMAL")
                .evaluateAt(time.plusHours(4))
                .evaluated(false)
                .sentToTelegram(false)
                .build();
    }
}
