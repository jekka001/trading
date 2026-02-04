package com.btc.collector.analysis;

import com.btc.collector.persistence.AlertHistoryEntity;
import com.btc.collector.persistence.AlertHistoryRepository;
import com.btc.collector.persistence.StrategyStatsEntity;
import com.btc.collector.persistence.StrategyStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Parallel confidence-aware evaluation system (Rate2.0).
 *
 * Scoring logic:
 * - Target hit + P >= 50%: +1 (counted)
 * - Target hit + P < 50%:   0 (neutral, not counted in rate)
 * - Target miss + P >= 50%: -1 (counted)
 * - Target miss + P < 50%: +1 (counted - correctly predicted low confidence)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConfidenceEvaluationService {

    private static final BigDecimal PROBABILITY_THRESHOLD = BigDecimal.valueOf(50);

    private final AlertHistoryRepository alertHistoryRepository;
    private final StrategyStatsRepository strategyStatsRepository;

    @Scheduled(fixedDelayString = "${confidence.evaluation.interval:900000}") // Default: 15 minutes
    public void scheduledEvaluation() {
        evaluatePendingAlerts();
    }

    @Transactional
    public int evaluatePendingAlerts() {
        List<AlertHistoryEntity> pendingAlerts = alertHistoryRepository.findReadyForConfidenceEvaluation();

        if (pendingAlerts.isEmpty()) {
            log.debug("No alerts ready for confidence evaluation");
            return 0;
        }

        log.info("Evaluating {} alerts for confidence (Rate2.0)", pendingAlerts.size());

        int evaluated = 0;
        for (AlertHistoryEntity alert : pendingAlerts) {
            if (evaluateConfidence(alert)) {
                evaluated++;
            }
        }

        log.info("Confidence evaluation complete: {} alerts processed", evaluated);
        return evaluated;
    }

    private boolean evaluateConfidence(AlertHistoryEntity alert) {
        try {
            boolean targetHit = alert.isTargetReached();
            BigDecimal probability = alert.getFinalProbability();
            boolean highProbability = probability != null && probability.compareTo(PROBABILITY_THRESHOLD) >= 0;

            int score = calculateScore(targetHit, highProbability);

            // Update alert
            alert.setConfidenceScore(score);
            alert.setConfidenceEvaluated(true);
            alertHistoryRepository.save(alert);

            // Update strategy stats
            String strategyId = alert.getStrategyId();
            if (strategyId != null) {
                StrategyStatsEntity stats = strategyStatsRepository.findById(strategyId)
                        .orElseGet(() -> StrategyStatsEntity.createNew(strategyId));
                stats.recordConfidenceScore(score);
                strategyStatsRepository.save(stats);
            }

            log.debug("Confidence evaluated: id={}, strategy={}, targetHit={}, highProb={}, score={}",
                    alert.getId(), strategyId, targetHit, highProbability, score);

            return true;
        } catch (Exception e) {
            log.error("Failed to evaluate confidence for alert {}: {}", alert.getId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Calculate confidence score based on target hit and probability.
     *
     * @param targetHit true if actualMaxPrice >= targetPrice
     * @param highProbability true if finalProbability >= 50%
     * @return +1 (positive), 0 (neutral), -1 (negative)
     */
    private int calculateScore(boolean targetHit, boolean highProbability) {
        if (targetHit && highProbability) {
            return 1;  // Correctly predicted success with high confidence
        } else if (targetHit && !highProbability) {
            return 0;  // Target hit but low confidence - neutral
        } else if (!targetHit && highProbability) {
            return -1; // Failed prediction with high confidence - penalty
        } else {
            return 1;  // Correctly predicted low confidence for a miss
        }
    }

    // ==================== Query Methods ====================

    public long getPendingCount() {
        return alertHistoryRepository.countPendingConfidenceEvaluation();
    }

    public long getPositiveCount() {
        return alertHistoryRepository.countConfidencePositive();
    }

    public long getNegativeCount() {
        return alertHistoryRepository.countConfidenceNegative();
    }

    public long getNeutralCount() {
        return alertHistoryRepository.countConfidenceNeutral();
    }

    public long getTotalScore() {
        return alertHistoryRepository.sumConfidenceScore();
    }

    public long getEvaluatedCount() {
        return alertHistoryRepository.countConfidenceEvaluated();
    }

    /**
     * Calculate global Rate2.0 (confidence rate).
     * Only counts positive and negative scores (neutral excluded).
     *
     * @return Rate2.0 as percentage, or null if no evaluated alerts
     */
    public Double getGlobalRate2() {
        long positive = getPositiveCount();
        long negative = getNegativeCount();
        long total = positive + negative;

        if (total == 0) {
            return null;
        }

        return 100.0 * positive / total;
    }
}
