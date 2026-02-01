package com.btc.collector.analysis;

import com.btc.collector.persistence.Candle15mRepository;
import com.btc.collector.persistence.PendingPredictionEntity;
import com.btc.collector.persistence.PendingPredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for storing and evaluating predictions.
 * Evaluates predictions after their time window expires and updates strategy stats.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PredictionEvaluationService {

    private final PendingPredictionRepository predictionRepository;
    private final Candle15mRepository candleRepository;
    private final StrategyTracker strategyTracker;

    /**
     * Store a new prediction for future evaluation.
     */
    @Transactional
    public void storePrediction(String strategyId, BigDecimal entryPrice,
                                BigDecimal predictedProfitPct, int predictedHours) {
        PendingPredictionEntity prediction = PendingPredictionEntity.create(
                strategyId, entryPrice, predictedProfitPct, predictedHours);
        predictionRepository.save(prediction);
        log.info("Stored prediction for strategy {}: entry={}, profit={}%, hours={}",
                strategyId, entryPrice, predictedProfitPct, predictedHours);
    }

    /**
     * Evaluate all predictions that have passed their evaluation time.
     */
    @Transactional
    public void evaluatePendingPredictions() {
        LocalDateTime now = LocalDateTime.now();
        List<PendingPredictionEntity> readyPredictions = predictionRepository.findReadyForEvaluation(now);

        if (readyPredictions.isEmpty()) {
            log.debug("No predictions ready for evaluation");
            return;
        }

        log.info("Evaluating {} pending predictions", readyPredictions.size());

        for (PendingPredictionEntity prediction : readyPredictions) {
            evaluatePrediction(prediction);
        }
    }

    private void evaluatePrediction(PendingPredictionEntity prediction) {
        try {
            // Find max price during the prediction window
            LocalDateTime startTime = prediction.getCreatedAt();
            LocalDateTime endTime = prediction.getEvaluateAt();

            BigDecimal maxPrice = candleRepository.findMaxHighPriceBetween(startTime, endTime);

            if (maxPrice == null) {
                log.warn("No candle data found for prediction evaluation: {} to {}",
                        startTime, endTime);
                // Don't mark as evaluated - try again later when data is available
                return;
            }

            // Calculate actual profit percentage
            BigDecimal entryPrice = prediction.getEntryPrice();
            BigDecimal actualProfitPct = maxPrice.subtract(entryPrice)
                    .divide(entryPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // Determine success: price reached at least half of predicted profit
            BigDecimal threshold = prediction.getPredictedProfitPct().divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            boolean success = actualProfitPct.compareTo(threshold) >= 0;

            // Update prediction record
            prediction.setEvaluated(true);
            prediction.setSuccess(success);
            prediction.setActualMaxPrice(maxPrice);
            prediction.setActualProfitPct(actualProfitPct);
            predictionRepository.save(prediction);

            // Update strategy stats
            if (success) {
                strategyTracker.recordSuccess(prediction.getStrategyId());
            } else {
                strategyTracker.recordFailure(prediction.getStrategyId());
            }

            log.info("Prediction evaluated for {}: predicted={}%, actual={}%, success={}",
                    prediction.getStrategyId(), prediction.getPredictedProfitPct(),
                    actualProfitPct, success);

        } catch (Exception e) {
            log.error("Error evaluating prediction {}: {}", prediction.getId(), e.getMessage());
        }
    }

    /**
     * Get count of pending (unevaluated) predictions.
     */
    public long getPendingCount() {
        return predictionRepository.countByEvaluatedFalse();
    }
}
