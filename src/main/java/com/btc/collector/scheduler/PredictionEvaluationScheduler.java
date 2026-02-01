package com.btc.collector.scheduler;

import com.btc.collector.analysis.PredictionEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler to evaluate pending predictions after their time window expires.
 * Runs every 15 minutes to check for predictions that need evaluation.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PredictionEvaluationScheduler {

    private final PredictionEvaluationService evaluationService;

    // Run at 45 seconds after each 15-minute mark (after signal analysis)
    @Scheduled(cron = "45 */15 * * * *")
    public void evaluatePredictions() {
        log.debug("Prediction evaluation triggered");

        try {
            long pendingCount = evaluationService.getPendingCount();
            if (pendingCount > 0) {
                log.info("Checking {} pending predictions for evaluation", pendingCount);
            }

            evaluationService.evaluatePendingPredictions();
        } catch (Exception e) {
            log.error("Error during prediction evaluation: {}", e.getMessage(), e);
        }
    }
}
