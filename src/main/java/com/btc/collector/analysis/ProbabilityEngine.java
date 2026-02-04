package com.btc.collector.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProbabilityEngine {

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final int SCALE = 2;

    // Matching criteria
    private static final BigDecimal RSI_TOLERANCE = BigDecimal.valueOf(5);

    private final PatternAnalyzer patternAnalyzer;
    private final StrategyTracker strategyTracker;
    private final StrategyEvaluator strategyEvaluator;
    private final PnlCoefficientService pnlCoefficientService;

    public ProbabilityResult analyze(MarketSnapshot currentSnapshot) {
        if (currentSnapshot == null) {
            log.warn("Cannot analyze null snapshot");
            return null;
        }

        if (!patternAnalyzer.isInitialized()) {
            log.warn("Pattern analyzer not initialized");
            return null;
        }

        List<HistoricalPattern> allPatterns = patternAnalyzer.getPatternsSnapshot();
        if (allPatterns.isEmpty()) {
            log.warn("No patterns available for analysis");
            return null;
        }

        // Filter matching patterns
        List<HistoricalPattern> matchedPatterns = allPatterns.stream()
                .filter(p -> matchesPattern(currentSnapshot, p.getSnapshot()))
                .collect(Collectors.toList());

        // Generate strategy ID for tracking
        String strategyId = strategyTracker.generateStrategyId(currentSnapshot);
        BigDecimal strategyWeight = strategyTracker.getWeight(strategyId);

        if (matchedPatterns.isEmpty()) {
            log.info("No matching patterns found for current snapshot (strategy: {})", strategyId);
            return ProbabilityResult.builder()
                    .probabilityUpPct(BigDecimal.ZERO)
                    .avgProfitPct(BigDecimal.ZERO)
                    .avgHoursToMax(BigDecimal.ZERO)
                    .matchedSamplesCount(0)
                    .currentSnapshot(currentSnapshot)
                    .strategyId(strategyId)
                    .strategyWeight(strategyWeight)
                    .weightedProbability(BigDecimal.ZERO)
                    .build();
        }

        // Calculate statistics from matched patterns
        long profitableCount = matchedPatterns.stream()
                .filter(HistoricalPattern::isProfitable)
                .count();

        BigDecimal probabilityUp = BigDecimal.valueOf(profitableCount)
                .divide(BigDecimal.valueOf(matchedPatterns.size()), MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal sumProfit = matchedPatterns.stream()
                .map(HistoricalPattern::getMaxProfitPct24h)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgProfit = sumProfit
                .divide(BigDecimal.valueOf(matchedPatterns.size()), MC)
                .setScale(SCALE, RoundingMode.HALF_UP);

        double sumHours = matchedPatterns.stream()
                .mapToInt(HistoricalPattern::getHoursToMax)
                .sum();

        BigDecimal avgHours = BigDecimal.valueOf(sumHours / matchedPatterns.size())
                .setScale(SCALE, RoundingMode.HALF_UP);

        // Use StrategyEvaluator to combine pattern probability with historical indicators
        EvaluationResult evalResult = strategyEvaluator.evaluate(probabilityUp, currentSnapshot, strategyId);

        log.info("Analysis complete: strategy={}, {} matched, {}% prob (final: {}%), {}% profit, weight={}, history={}",
                strategyId, matchedPatterns.size(), probabilityUp, evalResult.getFinalProbability(),
                avgProfit, evalResult.getStrategyWeight(),
                evalResult.isHistoryEnabled() ? evalResult.getHistoricalFactor() : "disabled");

        return ProbabilityResult.builder()
                .probabilityUpPct(probabilityUp)
                .avgProfitPct(avgProfit)
                .avgHoursToMax(avgHours)
                .matchedSamplesCount(matchedPatterns.size())
                .currentSnapshot(currentSnapshot)
                .strategyId(strategyId)
                .strategyWeight(evalResult.getStrategyWeight())
                .weightedProbability(evalResult.getFinalProbability())
                .historicalFactor(evalResult.getHistoricalFactor())
                .build();
    }

    /**
     * Analyze ALL strategies against current market snapshot.
     * Returns aggregated result with individual strategy evaluations.
     *
     * Each historical pattern has its own strategy ID based on its snapshot conditions.
     * This method groups patterns by their strategy and calculates probability for each.
     */
    public AggregatedAnalysisResult analyzeAllStrategies(MarketSnapshot currentSnapshot) {
        if (currentSnapshot == null) {
            log.warn("Cannot analyze null snapshot");
            return null;
        }

        if (!patternAnalyzer.isInitialized()) {
            log.warn("Pattern analyzer not initialized");
            return null;
        }

        List<HistoricalPattern> allPatterns = patternAnalyzer.getPatternsSnapshot();
        if (allPatterns.isEmpty()) {
            log.warn("No patterns available for analysis");
            return null;
        }

        // Group patterns by their strategy ID
        Map<String, List<HistoricalPattern>> patternsByStrategy = allPatterns.stream()
                .collect(Collectors.groupingBy(p ->
                        strategyTracker.generateStrategyId(p.getSnapshot())));

        log.debug("Analyzing {} strategies with {} total patterns",
                patternsByStrategy.size(), allPatterns.size());

        // Analyze each strategy
        List<StrategyAnalysisResult> strategyResults = new ArrayList<>();

        for (Map.Entry<String, List<HistoricalPattern>> entry : patternsByStrategy.entrySet()) {
            String strategyId = entry.getKey();
            List<HistoricalPattern> strategyPatterns = entry.getValue();

            // Filter patterns that match current snapshot conditions (RSI tolerance)
            List<HistoricalPattern> matchedPatterns = strategyPatterns.stream()
                    .filter(p -> matchesPatternRsi(currentSnapshot, p.getSnapshot()))
                    .collect(Collectors.toList());

            StrategyAnalysisResult result = analyzeStrategy(
                    strategyId, matchedPatterns, currentSnapshot);
            strategyResults.add(result);
        }

        // Create aggregated result
        AggregatedAnalysisResult aggregated = AggregatedAnalysisResult.aggregate(
                currentSnapshot, strategyResults);

        log.info("Multi-strategy analysis: {} strategies, {} with data, avg prob={}%, best={}",
                strategyResults.size(),
                aggregated.getStrategiesWithData(),
                aggregated.getWeightedAvgProbability(),
                aggregated.getBestStrategy() != null
                        ? aggregated.getBestStrategy().getStrategyId()
                        : "none");

        return aggregated;
    }

    /**
     * Analyze a single strategy with its matched patterns.
     */
    private StrategyAnalysisResult analyzeStrategy(
            String strategyId,
            List<HistoricalPattern> matchedPatterns,
            MarketSnapshot currentSnapshot) {

        BigDecimal strategyWeight = strategyTracker.getWeight(strategyId);

        if (matchedPatterns.isEmpty()) {
            return StrategyAnalysisResult.builder()
                    .strategyId(strategyId)
                    .baseProbability(BigDecimal.ZERO)
                    .finalProbability(BigDecimal.ZERO)
                    .strategyWeight(strategyWeight)
                    .avgProfitPct(BigDecimal.ZERO)
                    .avgHoursToMax(BigDecimal.ZERO)
                    .matchedPatterns(0)
                    .build();
        }

        // Calculate statistics
        long profitableCount = matchedPatterns.stream()
                .filter(HistoricalPattern::isProfitable)
                .count();

        BigDecimal probabilityUp = BigDecimal.valueOf(profitableCount)
                .divide(BigDecimal.valueOf(matchedPatterns.size()), MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal sumProfit = matchedPatterns.stream()
                .map(HistoricalPattern::getMaxProfitPct24h)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgProfit = sumProfit
                .divide(BigDecimal.valueOf(matchedPatterns.size()), MC)
                .setScale(SCALE, RoundingMode.HALF_UP);

        double sumHours = matchedPatterns.stream()
                .mapToInt(HistoricalPattern::getHoursToMax)
                .sum();

        BigDecimal avgHours = BigDecimal.valueOf(sumHours / matchedPatterns.size())
                .setScale(SCALE, RoundingMode.HALF_UP);

        // Apply strategy evaluation
        EvaluationResult evalResult = strategyEvaluator.evaluate(
                probabilityUp, currentSnapshot, strategyId);

        // Calculate PnL coefficient for Telegram boosting
        PnlCoefficientService.CoefficientResult coeffResult =
                pnlCoefficientService.calculateCoefficient(strategyId);

        // Apply coefficient to get boosted probability (for Telegram decision only)
        BigDecimal boostedProb = pnlCoefficientService.applyCoefficient(
                evalResult.getFinalProbability(), coeffResult.getCoefficient());

        // Log PnL boost if applied
        if (coeffResult.isBoosted()) {
            log.debug("PnL boost applied: strategy={}, pnl=${}, coeff={}, base={}%, boosted={}%",
                    strategyId, coeffResult.getTotalPnl(), coeffResult.getCoefficient(),
                    evalResult.getFinalProbability(), boostedProb);
        }

        return StrategyAnalysisResult.builder()
                .strategyId(strategyId)
                .baseProbability(probabilityUp)
                .finalProbability(evalResult.getFinalProbability())  // Original (stored in DB)
                .strategyWeight(evalResult.getStrategyWeight())
                .historicalFactor(evalResult.getHistoricalFactor())
                .avgProfitPct(avgProfit)
                .avgHoursToMax(avgHours)
                .matchedPatterns(matchedPatterns.size())
                // PnL coefficient fields (for Telegram decision)
                .pnlCoefficient(coeffResult.getCoefficient())
                .boostedProbability(boostedProb)
                .totalPnl(coeffResult.getTotalPnl())
                .build();
    }

    /**
     * Check if RSI matches within tolerance (used for cross-strategy matching).
     */
    private boolean matchesPatternRsi(MarketSnapshot current, MarketSnapshot historical) {
        if (current.getRsi() == null || historical.getRsi() == null) {
            return false;
        }
        BigDecimal rsiDiff = current.getRsi().subtract(historical.getRsi()).abs();
        return rsiDiff.compareTo(RSI_TOLERANCE) <= 0;
    }

    private boolean matchesPattern(MarketSnapshot current, MarketSnapshot historical) {
        if (current.getRsi() == null || historical.getRsi() == null) {
            return false;
        }

        // RSI within ±5
        BigDecimal rsiDiff = current.getRsi().subtract(historical.getRsi()).abs();
        if (rsiDiff.compareTo(RSI_TOLERANCE) > 0) {
            return false;
        }

        // EMA50 < EMA200 boolean match
        if (current.isEma50BelowEma200() != historical.isEma50BelowEma200()) {
            return false;
        }

        // Volume bucket match
        if (current.getVolumeBucket() != historical.getVolumeBucket()) {
            return false;
        }

        return true;
    }
}
