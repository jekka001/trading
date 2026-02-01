package com.btc.collector.analysis;

import com.btc.collector.persistence.Indicator15mEntity;
import com.btc.collector.strategy.MarketRegime;
import com.btc.collector.strategy.MarketRegimeDetector;
import com.btc.collector.strategy.MarketRegimeResult;
import com.btc.collector.strategy.StrategyDefinition;
import com.btc.collector.strategy.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Evaluates strategy signals by combining:
 * - Current market snapshot
 * - Historical indicators (from btc_indicator_15m)
 * - Strategy performance stats
 *
 * Produces a final probability that accounts for both pattern matching
 * and recent market conditions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyEvaluator {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final int SCALE = 4;

    // Number of historical indicators to analyze for trend
    private static final int HISTORY_LOOKBACK = 8; // 2 hours of 15m candles

    private final IndicatorLookupService indicatorLookupService;
    private final StrategyTracker strategyTracker;
    private final MarketRegimeDetector regimeDetector;
    private final StrategyRegistry strategyRegistry;

    @Value("${strategy.history.enabled:true}")
    private boolean historyEnabled;

    @Value("${strategy.history.weight:0.3}")
    private double historyWeight; // Weight of historical factor (0.3 = 30%)

    @Value("${strategy.regime.enabled:true}")
    private boolean regimeEnabled;

    @Value("${strategy.regime.weight:0.2}")
    private double regimeWeight; // Weight of regime adjustment (0.2 = 20%)

    /**
     * Evaluate and adjust probability based on historical indicators, strategy performance,
     * and market regime.
     *
     * Formula (per CLAUDE_PLAN_6):
     * finalProbability = baseProbability × regimeMultiplier × regimeConfidence
     *
     * @param baseProbability The base probability from pattern matching
     * @param currentSnapshot Current market snapshot
     * @param strategyId The strategy identifier
     * @return Adjusted probability result
     */
    public EvaluationResult evaluate(BigDecimal baseProbability, MarketSnapshot currentSnapshot, String strategyId) {
        BigDecimal strategyWeight = strategyTracker.getWeight(strategyId);

        // Get market regime with confidence
        MarketRegimeResult regimeResult = regimeDetector.detectRegime(currentSnapshot);
        MarketRegime regime = regimeResult.getRegime();
        double regimeConfidence = regimeResult.getConfidence();

        // Check if strategy is allowed in current regime
        boolean strategyAllowed = strategyRegistry.isStrategyAllowedInRegime(strategyId, regime);
        if (!strategyAllowed) {
            log.debug("Strategy {} not allowed in regime {}, suppressing signal", strategyId, regime);
            return EvaluationResult.builder()
                    .baseProbability(baseProbability)
                    .strategyWeight(strategyWeight)
                    .historicalFactor(BigDecimal.ONE)
                    .regimeFactor(BigDecimal.valueOf(regime.getMultiplier()))
                    .regimeConfidence(regimeConfidence)
                    .marketRegime(regime)
                    .finalProbability(BigDecimal.ZERO)
                    .historyEnabled(historyEnabled)
                    .strategyAllowedInRegime(false)
                    .build();
        }

        // Get regime factor (regime multiplier × type adjustment)
        BigDecimal regimeFactor = BigDecimal.valueOf(regime.getMultiplier());
        if (regimeEnabled) {
            StrategyDefinition.StrategyType strategyType = strategyRegistry.getStrategy(strategyId)
                    .map(StrategyDefinition::getType)
                    .orElse(StrategyDefinition.StrategyType.HYBRID);
            BigDecimal typeAdjustment = regimeDetector.getRegimeAdjustmentFactor(regime, strategyType);
            regimeFactor = regimeFactor.multiply(typeAdjustment);
        }

        if (!historyEnabled) {
            // Apply formula: base × regimeMultiplier × regimeConfidence × strategyWeight
            BigDecimal adjustedProbability = baseProbability
                    .multiply(regimeFactor)
                    .multiply(BigDecimal.valueOf(regimeConfidence))
                    .multiply(strategyWeight)
                    .setScale(SCALE, RoundingMode.HALF_UP);

            // Clamp to [0, 100]
            adjustedProbability = adjustedProbability.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));

            return EvaluationResult.builder()
                    .baseProbability(baseProbability)
                    .strategyWeight(strategyWeight)
                    .historicalFactor(BigDecimal.ONE)
                    .regimeFactor(regimeFactor)
                    .regimeConfidence(regimeConfidence)
                    .marketRegime(regime)
                    .finalProbability(adjustedProbability)
                    .historyEnabled(false)
                    .strategyAllowedInRegime(true)
                    .build();
        }

        // Get historical indicators
        List<Indicator15mEntity> recentIndicators = indicatorLookupService.getRecentIndicators(HISTORY_LOOKBACK);

        if (recentIndicators.size() < HISTORY_LOOKBACK) {
            log.debug("Not enough historical indicators for evaluation: {}", recentIndicators.size());
            BigDecimal adjustedProbability = baseProbability
                    .multiply(regimeFactor)
                    .multiply(BigDecimal.valueOf(regimeConfidence))
                    .multiply(strategyWeight)
                    .setScale(SCALE, RoundingMode.HALF_UP);

            adjustedProbability = adjustedProbability.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));

            return EvaluationResult.builder()
                    .baseProbability(baseProbability)
                    .strategyWeight(strategyWeight)
                    .historicalFactor(BigDecimal.ONE)
                    .regimeFactor(regimeFactor)
                    .regimeConfidence(regimeConfidence)
                    .marketRegime(regime)
                    .finalProbability(adjustedProbability)
                    .historyEnabled(true)
                    .strategyAllowedInRegime(true)
                    .build();
        }

        // Calculate historical factor
        BigDecimal historicalFactor = calculateHistoricalFactor(recentIndicators, currentSnapshot);

        // Combined formula with history:
        // final = base × regimeMultiplier × regimeConfidence × combinedWeight
        // where combinedWeight blends strategy performance and historical factor
        double baseWeight = 1.0 - historyWeight;
        BigDecimal strategyComponent = strategyWeight.multiply(BigDecimal.valueOf(baseWeight));
        BigDecimal historyComponent = historicalFactor.multiply(BigDecimal.valueOf(historyWeight));
        BigDecimal combinedWeight = strategyComponent.add(historyComponent);

        BigDecimal finalProbability = baseProbability
                .multiply(regimeFactor)
                .multiply(BigDecimal.valueOf(regimeConfidence))
                .multiply(combinedWeight)
                .setScale(SCALE, RoundingMode.HALF_UP);

        // Clamp to [0, 100]
        finalProbability = finalProbability.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));

        log.debug("Evaluation: base={}%, regime={} (mult={}, conf={}%), strategy={}, history={}, final={}%",
                baseProbability, regime, regimeFactor,
                String.format("%.0f", regimeConfidence * 100),
                strategyWeight, historicalFactor, finalProbability);

        return EvaluationResult.builder()
                .baseProbability(baseProbability)
                .strategyWeight(strategyWeight)
                .historicalFactor(historicalFactor)
                .regimeFactor(regimeFactor)
                .regimeConfidence(regimeConfidence)
                .marketRegime(regime)
                .finalProbability(finalProbability)
                .historyEnabled(true)
                .strategyAllowedInRegime(true)
                .build();
    }

    /**
     * Calculate historical factor based on recent indicator trends.
     * Returns a value between 0.5 and 1.5 to adjust probability.
     *
     * Factors considered:
     * - RSI momentum (rising = bullish)
     * - EMA alignment (EMA50 approaching EMA200 = potential crossover)
     * - Bollinger Band position (near lower band = potential bounce)
     * - ATR volatility (decreasing = consolidation before move)
     * - Volume trend (increasing = confirmation)
     */
    private BigDecimal calculateHistoricalFactor(List<Indicator15mEntity> indicators, MarketSnapshot current) {
        if (indicators.isEmpty()) {
            return BigDecimal.ONE;
        }

        double totalScore = 0;
        int factors = 0;

        // 1. RSI Momentum (compare first half vs second half average)
        Double rsiMomentum = calculateRsiMomentum(indicators);
        if (rsiMomentum != null) {
            // Positive momentum = boost, negative = reduce
            // Scale: -1 to +1 mapped to 0.7 to 1.3
            totalScore += 1.0 + (rsiMomentum * 0.3);
            factors++;
        }

        // 2. EMA Trend (is EMA50 approaching EMA200 from below = potential golden cross)
        Double emaTrend = calculateEmaTrend(indicators);
        if (emaTrend != null) {
            // Positive trend (EMA50 catching up to EMA200) = boost
            totalScore += 1.0 + (emaTrend * 0.2);
            factors++;
        }

        // 3. RSI Oversold Recovery (RSI was low and is now recovering)
        Double oversoldRecovery = calculateOversoldRecovery(indicators, current);
        if (oversoldRecovery != null) {
            totalScore += oversoldRecovery;
            factors++;
        }

        // 4. Bollinger Band Position (price near lower band = potential bounce)
        Double bbPosition = calculateBollingerBandPosition(indicators);
        if (bbPosition != null) {
            totalScore += bbPosition;
            factors++;
        }

        // 5. ATR Volatility Trend (decreasing volatility = consolidation)
        Double atrTrend = calculateAtrTrend(indicators);
        if (atrTrend != null) {
            totalScore += atrTrend;
            factors++;
        }

        // 6. Volume Trend (increasing volume = confirmation)
        Double volumeTrend = calculateVolumeTrend(indicators);
        if (volumeTrend != null) {
            totalScore += volumeTrend;
            factors++;
        }

        if (factors == 0) {
            return BigDecimal.ONE;
        }

        double avgScore = totalScore / factors;

        // Clamp to [0.5, 1.5]
        avgScore = Math.max(0.5, Math.min(1.5, avgScore));

        return BigDecimal.valueOf(avgScore).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Calculate RSI momentum: compare recent RSI to earlier RSI.
     * Returns -1 to +1 (negative = declining, positive = rising)
     */
    private Double calculateRsiMomentum(List<Indicator15mEntity> indicators) {
        int half = indicators.size() / 2;
        if (half < 2) return null;

        double earlySum = 0, lateSum = 0;
        int earlyCount = 0, lateCount = 0;

        for (int i = 0; i < indicators.size(); i++) {
            BigDecimal rsi = indicators.get(i).getRsi14();
            if (rsi == null) continue;

            if (i < half) {
                earlySum += rsi.doubleValue();
                earlyCount++;
            } else {
                lateSum += rsi.doubleValue();
                lateCount++;
            }
        }

        if (earlyCount == 0 || lateCount == 0) return null;

        double earlyAvg = earlySum / earlyCount;
        double lateAvg = lateSum / lateCount;

        // Normalize: difference of 10 RSI points = max effect
        double diff = (lateAvg - earlyAvg) / 10.0;
        return Math.max(-1, Math.min(1, diff));
    }

    /**
     * Calculate EMA trend: is the gap between EMA50 and EMA200 narrowing?
     * Returns -1 to +1 (negative = widening gap, positive = narrowing/crossing)
     */
    private Double calculateEmaTrend(List<Indicator15mEntity> indicators) {
        if (indicators.size() < 4) return null;

        Indicator15mEntity first = indicators.get(0);
        Indicator15mEntity last = indicators.get(indicators.size() - 1);

        if (first.getEma50() == null || first.getEma200() == null ||
            last.getEma50() == null || last.getEma200() == null) {
            return null;
        }

        // Calculate gap percentage
        double firstGap = first.getEma50().subtract(first.getEma200())
                .divide(first.getEma200(), MC).doubleValue();
        double lastGap = last.getEma50().subtract(last.getEma200())
                .divide(last.getEma200(), MC).doubleValue();

        // If gap is narrowing (becoming less negative or more positive), that's bullish
        double gapChange = lastGap - firstGap;

        // Normalize: 0.01 (1%) change = moderate effect
        return Math.max(-1, Math.min(1, gapChange * 50));
    }

    /**
     * Check for oversold recovery pattern.
     * Returns boost factor if RSI was oversold and is now recovering.
     */
    private Double calculateOversoldRecovery(List<Indicator15mEntity> indicators, MarketSnapshot current) {
        if (current.getRsi() == null) return null;

        // Check if any recent indicator had RSI < 30 (oversold)
        boolean wasOversold = indicators.stream()
                .anyMatch(i -> i.getRsi14() != null && i.getRsi14().doubleValue() < 30);

        // Check if current RSI is recovering (30-45 range)
        double currentRsi = current.getRsi().doubleValue();
        boolean isRecovering = currentRsi >= 30 && currentRsi <= 45;

        if (wasOversold && isRecovering) {
            // Boost for oversold recovery
            return 1.2;
        }

        return 1.0;
    }

    /**
     * Calculate Bollinger Band position factor.
     * Price near lower band = potential bounce (bullish).
     * Price near upper band = potential reversal (bearish for buy signal).
     */
    private Double calculateBollingerBandPosition(List<Indicator15mEntity> indicators) {
        Indicator15mEntity latest = indicators.get(indicators.size() - 1);

        if (latest.getBbLower() == null || latest.getBbUpper() == null || latest.getBbMiddle() == null) {
            return null;
        }

        // We need current price - use middle band as proxy if not available
        BigDecimal bbLower = latest.getBbLower();
        BigDecimal bbUpper = latest.getBbUpper();
        BigDecimal bbMiddle = latest.getBbMiddle();

        // Calculate band width
        double bandWidth = bbUpper.subtract(bbLower).doubleValue();
        if (bandWidth <= 0) return 1.0;

        // Position: 0 = at lower band, 1 = at upper band
        double position = bbMiddle.subtract(bbLower).doubleValue() / bandWidth;

        // Near lower band (position < 0.3) = bullish bounce potential
        if (position < 0.3) {
            return 1.15; // Boost
        }
        // Near upper band (position > 0.7) = bearish reversal potential
        else if (position > 0.7) {
            return 0.9; // Reduce
        }

        return 1.0;
    }

    /**
     * Calculate ATR volatility trend.
     * Decreasing ATR = consolidation, often precedes breakout.
     * Increasing ATR = high volatility, more risk.
     */
    private Double calculateAtrTrend(List<Indicator15mEntity> indicators) {
        if (indicators.size() < 4) return null;

        Indicator15mEntity first = indicators.get(0);
        Indicator15mEntity last = indicators.get(indicators.size() - 1);

        if (first.getAtr14() == null || last.getAtr14() == null) {
            return null;
        }

        double firstAtr = first.getAtr14().doubleValue();
        double lastAtr = last.getAtr14().doubleValue();

        if (firstAtr <= 0) return 1.0;

        // Calculate ATR change percentage
        double atrChangePct = (lastAtr - firstAtr) / firstAtr;

        // Decreasing ATR (consolidation) = slight boost (breakout potential)
        if (atrChangePct < -0.1) {
            return 1.1;
        }
        // Increasing ATR (high volatility) = slight reduction (more risk)
        else if (atrChangePct > 0.2) {
            return 0.95;
        }

        return 1.0;
    }

    /**
     * Calculate volume trend.
     * Increasing volume = confirmation of move.
     * Decreasing volume = lack of conviction.
     */
    private Double calculateVolumeTrend(List<Indicator15mEntity> indicators) {
        int half = indicators.size() / 2;
        if (half < 2) return null;

        double earlySum = 0, lateSum = 0;
        int earlyCount = 0, lateCount = 0;

        for (int i = 0; i < indicators.size(); i++) {
            BigDecimal avgVol = indicators.get(i).getAvgVolume20();
            if (avgVol == null) continue;

            if (i < half) {
                earlySum += avgVol.doubleValue();
                earlyCount++;
            } else {
                lateSum += avgVol.doubleValue();
                lateCount++;
            }
        }

        if (earlyCount == 0 || lateCount == 0) return null;

        double earlyAvg = earlySum / earlyCount;
        double lateAvg = lateSum / lateCount;

        if (earlyAvg <= 0) return 1.0;

        // Volume change percentage
        double volumeChangePct = (lateAvg - earlyAvg) / earlyAvg;

        // Increasing volume = confirmation boost
        if (volumeChangePct > 0.1) {
            return 1.1;
        }
        // Decreasing volume = lack of conviction
        else if (volumeChangePct < -0.1) {
            return 0.95;
        }

        return 1.0;
    }

    /**
     * Check if history-based evaluation is enabled.
     */
    public boolean isHistoryEnabled() {
        return historyEnabled;
    }
}
