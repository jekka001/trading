package com.btc.collector.analysis;

import com.btc.collector.persistence.AlertHistoryEntity;
import com.btc.collector.persistence.AlertHistoryRepository;
import com.btc.collector.persistence.Candle15mRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AlertEvaluationService {

    private final AlertHistoryRepository alertHistoryRepository;
    private final Candle15mRepository candleRepository;
    private final StrategyTracker strategyTracker;

    @PostConstruct
    public void init() {
        log.info("AlertEvaluationService initialized, checking for pending evaluations...");
        evaluatePendingAlerts();
    }

    @Scheduled(fixedDelayString = "${alert.evaluation.interval:900000}") // Default: 15 minutes
    public void scheduledEvaluation() {
        evaluatePendingAlerts();
    }

    @Transactional
    public void evaluatePendingAlerts() {
        LocalDateTime now = LocalDateTime.now();
        List<AlertHistoryEntity> pendingAlerts = alertHistoryRepository.findReadyForEvaluation(now);

        if (pendingAlerts.isEmpty()) {
            log.debug("No alerts ready for evaluation");
            return;
        }

        log.info("Evaluating {} pending alerts", pendingAlerts.size());

        for (AlertHistoryEntity alert : pendingAlerts) {
            evaluateAlert(alert);
        }
    }

    private void evaluateAlert(AlertHistoryEntity alert) {
        try {
            LocalDateTime alertTime = alert.getAlertTime();
            LocalDateTime evaluateAt = alert.getEvaluateAt();

            // Find max high price in the time window (efficient DB query)
            BigDecimal maxPrice = candleRepository.findMaxHighPriceBetween(alertTime, evaluateAt);

            if (maxPrice == null) {
                log.warn("No candles found for alert evaluation: alertId={}, timeRange=[{}, {}]",
                        alert.getId(), alertTime, evaluateAt);
                return;
            }

            // Calculate actual profit percentage
            BigDecimal currentPrice = alert.getCurrentPrice();
            BigDecimal actualProfitPct = maxPrice.subtract(currentPrice)
                    .divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // Determine success: price reached at least half of predicted profit
            BigDecimal threshold = alert.getPredictedProfitPct()
                    .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            boolean success = actualProfitPct.compareTo(threshold) >= 0;

            // Update alert record
            alert.setEvaluated(true);
            alert.setSuccess(success);
            alert.setActualMaxPrice(maxPrice);
            alert.setActualProfitPct(actualProfitPct);
            alert.setEvaluatedAt(LocalDateTime.now());

            alertHistoryRepository.save(alert);

            // Update strategy statistics (skip zero probability - not eligible)
            String strategyId = alert.getStrategyId();
            BigDecimal finalProb = alert.getFinalProbability();
            boolean isZeroProb = finalProb != null && finalProb.compareTo(BigDecimal.ZERO) == 0;
            if (strategyId != null && !isZeroProb) {
                if (success) {
                    strategyTracker.recordSuccess(strategyId);
                } else {
                    strategyTracker.recordFailure(strategyId);
                }
            }

            log.info("Alert evaluated: id={}, strategy={}, success={}, predicted={}%, actual={}%, threshold={}%",
                    alert.getId(), strategyId, success,
                    alert.getPredictedProfitPct(), actualProfitPct, threshold);

        } catch (Exception e) {
            log.error("Failed to evaluate alert {}: {}", alert.getId(), e.getMessage(), e);
        }
    }

    public long getPendingEvaluationCount() {
        return alertHistoryRepository.countPendingEvaluations();
    }

    public long getSuccessfulCount() {
        return alertHistoryRepository.countSuccessful();
    }

    public long getFailedCount() {
        return alertHistoryRepository.countFailed();
    }

    public Double getOverallSuccessRate() {
        return alertHistoryRepository.getOverallSuccessRate();
    }

    public List<AlertHistoryEntity> getRecentAlerts(int limit) {
        return alertHistoryRepository.findRecentAlerts(limit);
    }

    public long getTotalSignalCount() {
        return alertHistoryRepository.countTotal();
    }

    public long getSentToTelegramCount() {
        return alertHistoryRepository.countSentToTelegram();
    }

    public long getIgnoredZeroProbCount() {
        return alertHistoryRepository.countIgnoredZeroProb();
    }
}
