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
import java.math.RoundingMode;
import java.util.List;

/**
 * Profit-based evaluation service that simulates trades with fixed capital.
 *
 * This is independent of confidence scoring:
 * - A trade can lose money but still give positive confidence score
 * - A trade can win money but be neutral for confidence
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProfitEvaluationService {

    /**
     * Trade amount in USD per signal. Each signal = 1 trade.
     * Hardcoded constant, easy to change later.
     */
    public static final BigDecimal TRADE_AMOUNT_USD = BigDecimal.valueOf(10.0);

    private final AlertHistoryRepository alertHistoryRepository;
    private final StrategyStatsRepository strategyStatsRepository;

    @Scheduled(fixedDelayString = "${profit.evaluation.interval:900000}") // Default: 15 minutes
    public void scheduledEvaluation() {
        evaluatePendingAlerts();
    }

    @Transactional
    public int evaluatePendingAlerts() {
        List<AlertHistoryEntity> pendingAlerts = alertHistoryRepository.findReadyForProfitEvaluation();

        if (pendingAlerts.isEmpty()) {
            log.debug("No alerts ready for profit evaluation");
            return 0;
        }

        log.info("Evaluating {} alerts for profit", pendingAlerts.size());

        int evaluated = 0;
        for (AlertHistoryEntity alert : pendingAlerts) {
            if (evaluateProfit(alert)) {
                evaluated++;
            }
        }

        log.info("Profit evaluation complete: {} alerts processed", evaluated);
        return evaluated;
    }

    private boolean evaluateProfit(AlertHistoryEntity alert) {
        try {
            BigDecimal actualProfitPct = alert.getActualProfitPct();
            if (actualProfitPct == null) {
                log.warn("Alert {} has no actual_profit_pct, skipping profit evaluation", alert.getId());
                return false;
            }

            // Calculate actual profit in USD: TRADE_AMOUNT_USD * (actual_profit_pct / 100)
            BigDecimal actualProfitUsd = TRADE_AMOUNT_USD
                    .multiply(actualProfitPct)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

            // Update alert
            alert.setActualProfitUsd(actualProfitUsd);
            alertHistoryRepository.save(alert);

            // Update strategy stats
            String strategyId = alert.getStrategyId();
            if (strategyId != null) {
                StrategyStatsEntity stats = strategyStatsRepository.findById(strategyId)
                        .orElseGet(() -> StrategyStatsEntity.createNew(strategyId));
                stats.recordProfit(actualProfitPct, actualProfitUsd);
                strategyStatsRepository.save(stats);
            }

            log.debug("Profit evaluated: id={}, strategy={}, profitPct={}%, profitUsd=${}",
                    alert.getId(), strategyId, actualProfitPct, actualProfitUsd);

            return true;
        } catch (Exception e) {
            log.error("Failed to evaluate profit for alert {}: {}", alert.getId(), e.getMessage(), e);
            return false;
        }
    }

    // ==================== Query Methods ====================

    public long getPendingCount() {
        return alertHistoryRepository.countPendingProfitEvaluation();
    }

    /**
     * Get total PnL in USD (sum of all profits and losses).
     */
    public BigDecimal getTotalPnlUsd() {
        BigDecimal sum = alertHistoryRepository.sumActualProfitUsd();
        return sum != null ? sum.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /**
     * Get average actual profit percentage across all evaluated trades.
     */
    public BigDecimal getAvgActualProfitPct() {
        BigDecimal avg = alertHistoryRepository.avgActualProfitPct();
        return avg != null ? avg.setScale(2, RoundingMode.HALF_UP) : null;
    }

    /**
     * Get average actual profit in USD.
     */
    public BigDecimal getAvgActualProfitUsd() {
        BigDecimal avgPct = alertHistoryRepository.avgActualProfitPct();
        if (avgPct == null) {
            return null;
        }
        return TRADE_AMOUNT_USD
                .multiply(avgPct)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    /**
     * Get count of trades evaluated for profit.
     */
    public long getEvaluatedCount() {
        return alertHistoryRepository.countProfitEvaluated();
    }

    /**
     * Get the trade amount used for calculations.
     */
    public BigDecimal getTradeAmountUsd() {
        return TRADE_AMOUNT_USD;
    }
}
