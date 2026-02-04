package com.btc.collector.analysis;

import com.btc.collector.persistence.StrategyStatsEntity;
import com.btc.collector.persistence.StrategyStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Service that calculates PnL-based probability coefficients.
 *
 * The coefficient boosts probability for profitable strategies to increase
 * their chance of being sent to Telegram.
 *
 * Coefficient properties:
 * - Smooth: uses logarithmic function
 * - Bounded: capped at MAX_COEFFICIENT
 * - Non-aggressive: small PnL -> small boost, large PnL -> capped boost
 * - Neutral for losses: coefficient = 1.0 when PnL <= 0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PnlCoefficientService {

    /**
     * Minimum coefficient (no penalty, only neutral or boost).
     */
    private static final BigDecimal MIN_COEFFICIENT = BigDecimal.ONE;

    /**
     * Maximum coefficient cap to prevent runaway strategies.
     * 1.5 means max 50% boost to probability.
     */
    private static final BigDecimal MAX_COEFFICIENT = BigDecimal.valueOf(1.5);

    /**
     * Scaling factor for logarithmic growth.
     * Higher value = slower coefficient growth.
     */
    private static final double SCALE_FACTOR = 10.0;

    private final StrategyStatsRepository strategyStatsRepository;

    /**
     * Calculate PnL-based probability coefficient for a strategy.
     *
     * Formula: coefficient = 1.0 + ln(1 + pnl / SCALE_FACTOR)
     * Clamped to [MIN_COEFFICIENT, MAX_COEFFICIENT]
     *
     * @param strategyId the strategy to calculate coefficient for
     * @return coefficient result with debug info
     */
    public CoefficientResult calculateCoefficient(String strategyId) {
        if (strategyId == null) {
            return CoefficientResult.neutral(strategyId, null, "No strategy ID");
        }

        // Read existing total PnL from strategy stats
        StrategyStatsEntity stats = strategyStatsRepository.findById(strategyId).orElse(null);

        if (stats == null) {
            return CoefficientResult.neutral(strategyId, null, "Strategy not found");
        }

        BigDecimal totalPnl = stats.getTotalPnlUsd();
        if (totalPnl == null) {
            return CoefficientResult.neutral(strategyId, BigDecimal.ZERO, "No PnL data");
        }

        // If PnL <= 0, no boost (coefficient = 1.0)
        if (totalPnl.compareTo(BigDecimal.ZERO) <= 0) {
            return CoefficientResult.neutral(strategyId, totalPnl, "Non-positive PnL");
        }

        // Calculate coefficient: 1.0 + ln(1 + pnl / SCALE_FACTOR)
        double pnlValue = totalPnl.doubleValue();
        double rawCoefficient = 1.0 + Math.log(1.0 + pnlValue / SCALE_FACTOR);

        // Clamp to [MIN_COEFFICIENT, MAX_COEFFICIENT]
        BigDecimal coefficient = BigDecimal.valueOf(rawCoefficient)
                .max(MIN_COEFFICIENT)
                .min(MAX_COEFFICIENT)
                .setScale(4, RoundingMode.HALF_UP);

        log.debug("PnL coefficient for {}: pnl=${}, raw={}, clamped={}",
                strategyId, totalPnl, rawCoefficient, coefficient);

        return CoefficientResult.builder()
                .strategyId(strategyId)
                .totalPnl(totalPnl)
                .coefficient(coefficient)
                .reason("Profitable strategy boost")
                .build();
    }

    /**
     * Apply PnL coefficient to boost probability.
     *
     * @param baseProbability the original probability (unchanged for storage)
     * @param coefficient the PnL coefficient
     * @return boosted probability, clamped to [0, 100]
     */
    public BigDecimal applyCoefficient(BigDecimal baseProbability, BigDecimal coefficient) {
        if (baseProbability == null || coefficient == null) {
            return baseProbability;
        }

        // Zero probability signals are ignored
        if (baseProbability.compareTo(BigDecimal.ZERO) == 0) {
            return baseProbability;
        }

        BigDecimal boosted = baseProbability.multiply(coefficient)
                .setScale(2, RoundingMode.HALF_UP);

        // Clamp to [0, 100]
        if (boosted.compareTo(BigDecimal.ZERO) < 0) {
            boosted = BigDecimal.ZERO;
        }
        if (boosted.compareTo(BigDecimal.valueOf(100)) > 0) {
            boosted = BigDecimal.valueOf(100);
        }

        return boosted;
    }

    /**
     * Result of coefficient calculation with debug info.
     */
    @lombok.Data
    @lombok.Builder
    public static class CoefficientResult {
        private String strategyId;
        private BigDecimal totalPnl;
        private BigDecimal coefficient;
        private String reason;

        public static CoefficientResult neutral(String strategyId, BigDecimal totalPnl, String reason) {
            return CoefficientResult.builder()
                    .strategyId(strategyId)
                    .totalPnl(totalPnl)
                    .coefficient(BigDecimal.ONE)
                    .reason(reason)
                    .build();
        }

        public boolean isBoosted() {
            return coefficient != null && coefficient.compareTo(BigDecimal.ONE) > 0;
        }
    }
}
