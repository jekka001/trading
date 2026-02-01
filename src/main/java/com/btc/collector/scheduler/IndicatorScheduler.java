package com.btc.collector.scheduler;

import com.btc.collector.analysis.PatternAnalyzer;
import com.btc.collector.service.IndicatorCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class IndicatorScheduler {

    private final IndicatorCalculationService indicatorService;
    private final PatternAnalyzer patternAnalyzer;

    // Run 20 seconds after each 15-minute mark (after candle sync at :10)
    @Scheduled(cron = "20 */15 * * * *")
    public void calculateIndicators() {
        log.info("Scheduled indicator calculation triggered");

        if (indicatorService.isCalcInProgress()) {
            log.info("Calculation in progress, skipping scheduled run");
            return;
        }

        try {
            indicatorService.calculateNewIndicators();

            // After indicators are calculated, build patterns for new indicators
            buildPatternsForNewIndicators();
        } catch (Exception e) {
            log.error("Error during scheduled indicator calculation: {}", e.getMessage());
        }
    }

    /**
     * Build patterns for indicators that don't have corresponding patterns yet.
     */
    private void buildPatternsForNewIndicators() {
        if (patternAnalyzer.isBuildInProgress()) {
            log.info("Pattern build in progress, skipping");
            return;
        }

        try {
            log.info("Building patterns for new indicators...");
            PatternAnalyzer.ResumeResult result = patternAnalyzer.resumePatternBuilding();

            if (result.patternsBuilt() > 0) {
                log.info("Built {} new patterns (skipped {} existing)",
                    result.patternsBuilt(), result.patternsSkipped());
            } else {
                log.debug("No new patterns to build");
            }
        } catch (Exception e) {
            log.error("Error building patterns after indicator calculation: {}", e.getMessage());
        }
    }
}
