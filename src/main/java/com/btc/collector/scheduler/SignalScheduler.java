package com.btc.collector.scheduler;

import com.btc.collector.analysis.*;
import com.btc.collector.persistence.Candle15mEntity;
import com.btc.collector.persistence.Candle15mRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class SignalScheduler {

    private static final int REQUIRED_CANDLES = 100;

    private final Candle15mRepository candleRepository;
    private final IndicatorEngine indicatorEngine;
    private final PatternAnalyzer patternAnalyzer;
    private final ProbabilityEngine probabilityEngine;
    private final SignalAlertService alertService;
    private final StrategyTracker strategyTracker;

    // Run 30 seconds after each 15-minute mark (after candle and indicator sync)
    @Scheduled(cron = "30 */15 * * * *")
    public void analyzeMarket() {
        log.info("Signal analysis triggered");

        if (!patternAnalyzer.isInitialized()) {
            log.info("Pattern analyzer not initialized, skipping signal analysis");
            return;
        }

        try {
            // Update pattern dataset with new candle
            patternAnalyzer.updateWithNewCandle();

            // Get recent candles for current snapshot
            List<Candle15mEntity> candles = candleRepository.findAllOrderByOpenTimeAsc();

            if (candles.size() < REQUIRED_CANDLES) {
                log.warn("Not enough candles for analysis: {}", candles.size());
                return;
            }

            // Get last 100 candles for indicator calculation
            List<Candle15mEntity> recentCandles = candles.subList(
                    candles.size() - REQUIRED_CANDLES, candles.size());

            // Calculate current market snapshot
            MarketSnapshot snapshot = indicatorEngine.calculate(recentCandles);
            if (snapshot == null) {
                log.warn("Failed to calculate market snapshot");
                return;
            }

            log.info("Current snapshot - RSI: {}, EMA50<EMA200: {}, Volume: {}%",
                    snapshot.getRsi(), snapshot.isEma50BelowEma200(), snapshot.getVolumeChangePct());

            // Analyze ALL strategies against current snapshot
            AggregatedAnalysisResult aggregated = probabilityEngine.analyzeAllStrategies(snapshot);
            if (aggregated == null) {
                log.warn("Failed to analyze probability");
                return;
            }

            // Log aggregated result for each new candle
            Candle15mEntity latestCandle = recentCandles.get(recentCandles.size() - 1);
            StrategyAnalysisResult bestStrategy = aggregated.getBestStrategy();
            log.info("New Candle: {} | Price: {} | Strategies: {} | Avg Prob: {}% | Best: {} ({}%)",
                    latestCandle.getOpenTime(),
                    latestCandle.getClosePrice(),
                    aggregated.getStrategiesWithData(),
                    aggregated.getWeightedAvgProbability(),
                    bestStrategy != null ? bestStrategy.getStrategyId() : "none",
                    bestStrategy != null ? bestStrategy.getFinalProbability() : "0");

            // Process aggregated analysis: save all strategies, send aggregated alert
            alertService.processAggregatedAnalysis(aggregated);

            // Check for strategy degradation alerts on all strategies with data
            for (StrategyAnalysisResult strategy : aggregated.getStrategyResults()) {
                if (strategy.hasData()) {
                    checkStrategyDegradation(strategy.getStrategyId());
                }
            }

        } catch (Exception e) {
            log.error("Error during signal analysis: {}", e.getMessage(), e);
        }
    }

    private void checkStrategyDegradation(String strategyId) {
        if (strategyId == null) return;

        if (strategyTracker.needsDegradationAlert(strategyId)) {
            strategyTracker.get(strategyId).ifPresent(stats -> {
                alertService.sendDegradationAlert(stats);
                strategyTracker.markDegradationAlerted(strategyId);
            });
        }
    }
}
