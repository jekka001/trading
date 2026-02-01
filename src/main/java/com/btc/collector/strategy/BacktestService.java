package com.btc.collector.strategy;

import com.btc.collector.persistence.HistoricalPatternEntity;
import com.btc.collector.persistence.HistoricalPatternRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Service for backtesting strategies against historical data.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BacktestService {

    private final HistoricalPatternRepository patternRepository;
    private final StrategyRegistry strategyRegistry;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Backtest result containing all metrics.
     */
    @Data
    @Builder
    public static class BacktestResult {
        private String strategyId;
        private String strategyName;
        private LocalDateTime startDate;
        private LocalDateTime endDate;

        // Signal counts
        private int totalSignals;
        private int profitableSignals;
        private int unprofitableSignals;

        // Performance metrics
        private BigDecimal winRate;
        private BigDecimal avgProfitPct;
        private BigDecimal avgLossPct;
        private BigDecimal totalProfitPct;
        private BigDecimal maxProfitPct;
        private BigDecimal maxDrawdownPct;
        private BigDecimal profitFactor;

        // Time metrics
        private BigDecimal avgHoursToMax;

        // Risk metrics
        private BigDecimal sharpeRatio;
        private BigDecimal sortinRatio;

        private long executionTimeMs;

        public String toFormattedString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Backtest Results ===\n");
            sb.append("Strategy: ").append(strategyId).append("\n");
            if (strategyName != null) {
                sb.append("Name: ").append(strategyName).append("\n");
            }
            sb.append("Period: ").append(startDate.format(DATE_FORMAT))
                    .append(" to ").append(endDate.format(DATE_FORMAT)).append("\n\n");

            sb.append("📊 Signals:\n");
            sb.append("  Total: ").append(totalSignals).append("\n");
            sb.append("  Profitable: ").append(profitableSignals)
                    .append(" (").append(winRate).append("%)\n");
            sb.append("  Unprofitable: ").append(unprofitableSignals).append("\n\n");

            sb.append("💰 Performance:\n");
            sb.append("  Win Rate: ").append(winRate).append("%\n");
            sb.append("  Avg Profit: ").append(formatProfit(avgProfitPct)).append("%\n");
            sb.append("  Avg Loss: ").append(formatProfit(avgLossPct)).append("%\n");
            sb.append("  Total P/L: ").append(formatProfit(totalProfitPct)).append("%\n");
            sb.append("  Max Profit: ").append(formatProfit(maxProfitPct)).append("%\n");
            sb.append("  Max Drawdown: ").append(formatProfit(maxDrawdownPct)).append("%\n");
            sb.append("  Profit Factor: ").append(profitFactor).append("\n\n");

            sb.append("⏱ Time:\n");
            sb.append("  Avg Time to Max: ").append(avgHoursToMax).append(" hours\n\n");

            sb.append("Execution: ").append(executionTimeMs).append("ms");

            return sb.toString();
        }

        private String formatProfit(BigDecimal value) {
            if (value == null) return "N/A";
            return (value.compareTo(BigDecimal.ZERO) > 0 ? "+" : "") + value.toString();
        }
    }

    /**
     * Run backtest for a strategy with default date range (all data).
     */
    public BacktestResult backtest(String strategyId) {
        return backtest(strategyId, null, null);
    }

    /**
     * Run backtest for a strategy within a date range.
     */
    public BacktestResult backtest(String strategyId, LocalDateTime startDate, LocalDateTime endDate) {
        long startTime = System.currentTimeMillis();

        log.info("Running backtest for strategy: {} ({} to {})",
                strategyId,
                startDate != null ? startDate.format(DATE_FORMAT) : "beginning",
                endDate != null ? endDate.format(DATE_FORMAT) : "now");

        // Get patterns for this strategy
        List<HistoricalPatternEntity> patterns = patternRepository.findByStrategyId(strategyId);

        // Filter by date range if specified
        if (startDate != null) {
            patterns = patterns.stream()
                    .filter(p -> !p.getCandleTime().isBefore(startDate))
                    .toList();
        }
        if (endDate != null) {
            patterns = patterns.stream()
                    .filter(p -> !p.getCandleTime().isAfter(endDate))
                    .toList();
        }

        // Filter to evaluated patterns only
        List<HistoricalPatternEntity> evaluated = patterns.stream()
                .filter(HistoricalPatternEntity::isEvaluated)
                .filter(p -> p.getMaxProfitPct() != null)
                .toList();

        if (evaluated.isEmpty()) {
            return BacktestResult.builder()
                    .strategyId(strategyId)
                    .strategyName(getStrategyName(strategyId))
                    .startDate(startDate != null ? startDate : LocalDateTime.now().minusYears(1))
                    .endDate(endDate != null ? endDate : LocalDateTime.now())
                    .totalSignals(0)
                    .winRate(BigDecimal.ZERO)
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        // Calculate metrics
        List<HistoricalPatternEntity> profitable = evaluated.stream()
                .filter(p -> p.getMaxProfitPct().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        List<HistoricalPatternEntity> unprofitable = evaluated.stream()
                .filter(p -> p.getMaxProfitPct().compareTo(BigDecimal.ZERO) <= 0)
                .toList();

        // Win rate
        BigDecimal winRate = BigDecimal.valueOf(profitable.size())
                .divide(BigDecimal.valueOf(evaluated.size()), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        // Average profit
        BigDecimal avgProfit = profitable.isEmpty() ? BigDecimal.ZERO :
                profitable.stream()
                        .map(HistoricalPatternEntity::getMaxProfitPct)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(profitable.size()), 2, RoundingMode.HALF_UP);

        // Average loss
        BigDecimal avgLoss = unprofitable.isEmpty() ? BigDecimal.ZERO :
                unprofitable.stream()
                        .map(HistoricalPatternEntity::getMaxProfitPct)
                        .map(BigDecimal::abs)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(unprofitable.size()), 2, RoundingMode.HALF_UP);

        // Total profit
        BigDecimal totalProfit = evaluated.stream()
                .map(HistoricalPatternEntity::getMaxProfitPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Max profit
        BigDecimal maxProfit = evaluated.stream()
                .map(HistoricalPatternEntity::getMaxProfitPct)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        // Max drawdown
        BigDecimal maxDrawdown = evaluated.stream()
                .map(HistoricalPatternEntity::getMaxProfitPct)
                .filter(p -> p.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        // Profit factor
        BigDecimal grossProfit = profitable.stream()
                .map(HistoricalPatternEntity::getMaxProfitPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = unprofitable.stream()
                .map(HistoricalPatternEntity::getMaxProfitPct)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal profitFactor = grossLoss.compareTo(BigDecimal.ZERO) == 0 ?
                BigDecimal.valueOf(999) :
                grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP);

        // Average hours to max
        BigDecimal avgHoursToMax = BigDecimal.valueOf(
                evaluated.stream()
                        .map(HistoricalPatternEntity::getHoursToMax)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .average()
                        .orElse(0)
        ).setScale(1, RoundingMode.HALF_UP);

        // Date range
        LocalDateTime actualStart = evaluated.stream()
                .map(HistoricalPatternEntity::getCandleTime)
                .min(LocalDateTime::compareTo)
                .orElse(startDate != null ? startDate : LocalDateTime.now());

        LocalDateTime actualEnd = evaluated.stream()
                .map(HistoricalPatternEntity::getCandleTime)
                .max(LocalDateTime::compareTo)
                .orElse(endDate != null ? endDate : LocalDateTime.now());

        long executionTime = System.currentTimeMillis() - startTime;

        log.info("Backtest complete: {} signals, {}% win rate, {}% total profit in {}ms",
                evaluated.size(), winRate, totalProfit, executionTime);

        return BacktestResult.builder()
                .strategyId(strategyId)
                .strategyName(getStrategyName(strategyId))
                .startDate(actualStart)
                .endDate(actualEnd)
                .totalSignals(evaluated.size())
                .profitableSignals(profitable.size())
                .unprofitableSignals(unprofitable.size())
                .winRate(winRate)
                .avgProfitPct(avgProfit)
                .avgLossPct(avgLoss)
                .totalProfitPct(totalProfit)
                .maxProfitPct(maxProfit)
                .maxDrawdownPct(maxDrawdown)
                .profitFactor(profitFactor)
                .avgHoursToMax(avgHoursToMax)
                .executionTimeMs(executionTime)
                .build();
    }

    /**
     * Run backtest for all strategies.
     */
    public List<BacktestResult> backtestAll() {
        return strategyRegistry.getAllStrategies().stream()
                .map(s -> backtest(s.getStrategyId()))
                .filter(r -> r.getTotalSignals() > 0)
                .sorted((a, b) -> b.getWinRate().compareTo(a.getWinRate()))
                .toList();
    }

    /**
     * Get strategy name from registry.
     */
    private String getStrategyName(String strategyId) {
        return strategyRegistry.getStrategy(strategyId)
                .map(StrategyDefinition::getHumanReadableName)
                .orElse(null);
    }

    /**
     * Get available strategies for backtesting.
     */
    public List<String> getAvailableStrategies() {
        return patternRepository.findAll().stream()
                .map(HistoricalPatternEntity::getStrategyId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }
}
