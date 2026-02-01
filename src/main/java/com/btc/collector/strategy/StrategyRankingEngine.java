package com.btc.collector.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine for ranking strategies and determining which are allowed to trigger alerts.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyRankingEngine {

    private final StrategyMetricsService metricsService;
    private final StrategyRegistry strategyRegistry;

    @Value("${strategy.ranking.top-n:5}")
    private int topNStrategies;

    @Value("${strategy.ranking.min-score:30}")
    private double minScore;

    // Cached rankings
    private final Map<String, StrategyMetrics> rankedStrategies = new ConcurrentHashMap<>();
    private final Set<String> allowedStrategies = ConcurrentHashMap.newKeySet();
    private LocalDateTime lastRankingTime = null;

    /**
     * Update strategy rankings.
     * Called periodically and on demand.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void updateRankings() {
        log.info("Updating strategy rankings...");

        List<StrategyMetrics> allMetrics = metricsService.calculateAllMetrics();

        // Clear and update cache
        rankedStrategies.clear();
        allowedStrategies.clear();

        int rank = 1;
        for (StrategyMetrics metrics : allMetrics) {
            metrics.setRank(rank++);
            rankedStrategies.put(metrics.getStrategyId(), metrics);

            // Allow top N strategies with minimum score
            if (metrics.getRank() <= topNStrategies &&
                metrics.getScore() != null &&
                metrics.getScore().doubleValue() >= minScore &&
                metrics.hasReliableData()) {

                allowedStrategies.add(metrics.getStrategyId());
            }
        }

        lastRankingTime = LocalDateTime.now();

        log.info("Strategy rankings updated: {} total, {} allowed for alerts",
                rankedStrategies.size(), allowedStrategies.size());

        // Log top strategies
        allMetrics.stream()
                .limit(5)
                .forEach(m -> log.info("  #{} {} - Score: {}, Win Rate: {}%, EV: {}",
                        m.getRank(), m.getStrategyId(), m.getScore(),
                        m.getWinRate(), m.getExpectedValue()));
    }

    /**
     * Check if a strategy is allowed to trigger alerts.
     */
    public boolean isStrategyAllowed(String strategyId) {
        // If no rankings yet, allow all
        if (allowedStrategies.isEmpty() && rankedStrategies.isEmpty()) {
            return true;
        }

        return allowedStrategies.contains(strategyId);
    }

    /**
     * Get rank for a strategy.
     */
    public int getStrategyRank(String strategyId) {
        StrategyMetrics metrics = rankedStrategies.get(strategyId);
        return metrics != null ? metrics.getRank() : Integer.MAX_VALUE;
    }

    /**
     * Get metrics for a strategy.
     */
    public Optional<StrategyMetrics> getStrategyMetrics(String strategyId) {
        return Optional.ofNullable(rankedStrategies.get(strategyId));
    }

    /**
     * Get all ranked strategies.
     */
    public List<StrategyMetrics> getAllRankedStrategies() {
        List<StrategyMetrics> sorted = new ArrayList<>(rankedStrategies.values());
        sorted.sort(Comparator.comparingInt(StrategyMetrics::getRank));
        return sorted;
    }

    /**
     * Get allowed strategies.
     */
    public Set<String> getAllowedStrategies() {
        return Collections.unmodifiableSet(allowedStrategies);
    }

    /**
     * Get top N strategies.
     */
    public List<StrategyMetrics> getTopStrategies() {
        return getAllRankedStrategies().stream()
                .limit(topNStrategies)
                .toList();
    }

    /**
     * Get last ranking time.
     */
    public LocalDateTime getLastRankingTime() {
        return lastRankingTime;
    }

    /**
     * Force ranking update.
     */
    public void forceUpdate() {
        updateRankings();
    }

    /**
     * Get ranking summary for display.
     */
    public String getRankingSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Strategy Rankings (").append(lastRankingTime != null ?
                lastRankingTime.toString() : "not calculated").append(")\n");
        sb.append("Allowed for alerts: ").append(allowedStrategies.size()).append("/")
                .append(rankedStrategies.size()).append("\n\n");

        List<StrategyMetrics> top = getTopStrategies();
        for (StrategyMetrics m : top) {
            sb.append(String.format("#%d %s\n", m.getRank(), m.getStrategyId()));
            sb.append(String.format("   Score: %.1f | Win: %.1f%% | EV: %.2f%% | Signals: %d\n",
                    m.getScore().doubleValue(),
                    m.getWinRate().doubleValue(),
                    m.getExpectedValue().doubleValue(),
                    m.getEvaluatedSignals()));
        }

        return sb.toString();
    }
}
