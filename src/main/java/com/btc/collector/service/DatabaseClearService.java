package com.btc.collector.service;

import com.btc.collector.persistence.Candle15mRepository;
import com.btc.collector.persistence.HistoricalPatternRepository;
import com.btc.collector.persistence.Indicator15mRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DatabaseClearService {

    private final Candle15mRepository candleRepository;
    private final Indicator15mRepository indicatorRepository;
    private final HistoricalPatternRepository patternRepository;

    /**
     * Clear all data from the database (candles, indicators, patterns).
     * Use with caution - this deletes ALL historical data!
     *
     * @return Summary of deleted records
     */
    @Transactional
    public ClearResult clearAllData() {
        log.warn("=== CLEARING ALL DATABASE DATA ===");

        // Get counts before deletion
        long candleCount = candleRepository.count();
        long indicatorCount = indicatorRepository.count();
        long patternCount = patternRepository.countPatterns();

        log.info("Before clear: {} candles, {} indicators, {} patterns",
                candleCount, indicatorCount, patternCount);

        // Delete in order: patterns -> indicators -> candles (due to dependencies)
        log.info("Deleting patterns...");
        patternRepository.deleteAllPatterns();

        log.info("Deleting indicators...");
        indicatorRepository.deleteAll();

        log.info("Deleting candles...");
        candleRepository.deleteAll();

        log.warn("=== DATABASE CLEARED ===");

        return new ClearResult(candleCount, indicatorCount, patternCount);
    }

    /**
     * Clear only patterns (keeps candles and indicators).
     */
    @Transactional
    public long clearPatterns() {
        long count = patternRepository.countPatterns();
        patternRepository.deleteAllPatterns();
        log.info("Cleared {} patterns", count);
        return count;
    }

    /**
     * Clear patterns and indicators (keeps candles).
     */
    @Transactional
    public ClearResult clearPatternsAndIndicators() {
        long indicatorCount = indicatorRepository.count();
        long patternCount = patternRepository.countPatterns();

        patternRepository.deleteAllPatterns();
        indicatorRepository.deleteAll();

        log.info("Cleared {} patterns and {} indicators", patternCount, indicatorCount);
        return new ClearResult(0, indicatorCount, patternCount);
    }

    public record ClearResult(long candlesDeleted, long indicatorsDeleted, long patternsDeleted) {
        public long total() {
            return candlesDeleted + indicatorsDeleted + patternsDeleted;
        }
    }
}
