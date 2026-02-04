package com.btc.collector.analysis;

import com.btc.collector.persistence.AlertHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Service that provides statistics ONLY for signals that were actually sent to Telegram.
 * This is fully isolated from global and strategy-wide statistics.
 *
 * A signal is considered a Telegram alert if:
 * - sent_to_telegram == true
 * - probability > 0
 * - evaluated == true (non-pending)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramStatsService {

    private static final BigDecimal TRADE_AMOUNT_USD = ProfitEvaluationService.TRADE_AMOUNT_USD;

    private final AlertHistoryRepository alertHistoryRepository;

    // ==================== Telegram Evaluation v1 (Success/Failure) ====================

    /**
     * Get count of Telegram alerts that have been evaluated.
     */
    public long getEvaluatedCount() {
        return alertHistoryRepository.countTelegramEvaluated();
    }

    /**
     * Get count of successful Telegram alerts.
     */
    public long getSuccessfulCount() {
        return alertHistoryRepository.countTelegramSuccessful();
    }

    /**
     * Get count of failed Telegram alerts.
     */
    public long getFailedCount() {
        return alertHistoryRepository.countTelegramFailed();
    }

    /**
     * Get success rate for Telegram alerts only.
     */
    public Double getSuccessRate() {
        return alertHistoryRepository.getTelegramSuccessRate();
    }

    // ==================== Telegram Confidence Evaluation v2 ====================

    /**
     * Get count of positive confidence scores for Telegram alerts.
     */
    public long getConfidencePositiveCount() {
        return alertHistoryRepository.countTelegramConfidencePositive();
    }

    /**
     * Get count of negative confidence scores for Telegram alerts.
     */
    public long getConfidenceNegativeCount() {
        return alertHistoryRepository.countTelegramConfidenceNegative();
    }

    /**
     * Get total confidence score for Telegram alerts.
     */
    public long getTotalConfidenceScore() {
        return alertHistoryRepository.sumTelegramConfidenceScore();
    }

    /**
     * Get count of confidence evaluated Telegram alerts.
     */
    public long getConfidenceEvaluatedCount() {
        return alertHistoryRepository.countTelegramConfidenceEvaluated();
    }

    /**
     * Get confidence rate (Rate2.0) for Telegram alerts only.
     * Only counts positive and negative (neutral excluded).
     */
    public Double getConfidenceRate() {
        long positive = getConfidencePositiveCount();
        long negative = getConfidenceNegativeCount();
        long total = positive + negative;

        if (total == 0) {
            return null;
        }

        return 100.0 * positive / total;
    }

    // ==================== Telegram Profit Evaluation ====================

    /**
     * Get total PnL in USD for Telegram alerts only.
     */
    public BigDecimal getTotalPnlUsd() {
        BigDecimal sum = alertHistoryRepository.sumTelegramActualProfitUsd();
        return sum != null ? sum.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /**
     * Get average actual profit percentage for Telegram alerts only.
     */
    public BigDecimal getAvgActualProfitPct() {
        BigDecimal avg = alertHistoryRepository.avgTelegramActualProfitPct();
        return avg != null ? avg.setScale(2, RoundingMode.HALF_UP) : null;
    }

    /**
     * Get average actual profit in USD for Telegram alerts only.
     */
    public BigDecimal getAvgActualProfitUsd() {
        BigDecimal avgPct = alertHistoryRepository.avgTelegramActualProfitPct();
        if (avgPct == null) {
            return null;
        }
        return TRADE_AMOUNT_USD
                .multiply(avgPct)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    /**
     * Get count of Telegram trades evaluated for profit.
     */
    public long getProfitEvaluatedCount() {
        return alertHistoryRepository.countTelegramProfitEvaluated();
    }

    // ==================== Telegram Strategy Stats ====================

    /**
     * Get per-strategy statistics for Telegram alerts only.
     * Only strategies that had at least 1 Telegram alert appear.
     */
    public List<TelegramStrategyStats> getStrategyStats() {
        List<String> strategyIds = alertHistoryRepository.findTelegramStrategyIds();
        List<TelegramStrategyStats> result = new ArrayList<>();

        for (String strategyId : strategyIds) {
            TelegramStrategyStats stats = calculateStrategyStats(strategyId);
            if (stats.getSignalsSent() > 0) {
                result.add(stats);
            }
        }

        // Sort by total PnL descending
        result.sort(Comparator.comparing(
                s -> s.getTotalPnlUsd() != null ? s.getTotalPnlUsd() : BigDecimal.ZERO,
                Comparator.reverseOrder()
        ));

        return result;
    }

    private TelegramStrategyStats calculateStrategyStats(String strategyId) {
        long signalsSent = alertHistoryRepository.countTelegramByStrategy(strategyId);
        long successful = alertHistoryRepository.countTelegramSuccessfulByStrategy(strategyId);
        long failed = alertHistoryRepository.countTelegramFailedByStrategy(strategyId);

        // Calculate success rate
        BigDecimal successRate = null;
        long evaluated = successful + failed;
        if (evaluated > 0) {
            successRate = BigDecimal.valueOf(successful)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(evaluated), 2, RoundingMode.HALF_UP);
        }

        // Confidence stats
        long confPositive = alertHistoryRepository.countTelegramConfidencePositiveByStrategy(strategyId);
        long confNegative = alertHistoryRepository.countTelegramConfidenceNegativeByStrategy(strategyId);
        BigDecimal rate2 = null;
        long confTotal = confPositive + confNegative;
        if (confTotal > 0) {
            rate2 = BigDecimal.valueOf(confPositive)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(confTotal), 2, RoundingMode.HALF_UP);
        }

        // Profit stats
        BigDecimal avgProfitPct = alertHistoryRepository.avgTelegramActualProfitPctByStrategy(strategyId);
        if (avgProfitPct != null) {
            avgProfitPct = avgProfitPct.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal totalPnlUsd = alertHistoryRepository.sumTelegramActualProfitUsdByStrategy(strategyId);
        if (totalPnlUsd != null) {
            totalPnlUsd = totalPnlUsd.setScale(2, RoundingMode.HALF_UP);
        }

        return TelegramStrategyStats.builder()
                .strategyId(strategyId)
                .signalsSent(signalsSent)
                .successful(successful)
                .failed(failed)
                .successRate(successRate)
                .rate2(rate2)
                .avgProfitPct(avgProfitPct)
                .totalPnlUsd(totalPnlUsd)
                .build();
    }
}
