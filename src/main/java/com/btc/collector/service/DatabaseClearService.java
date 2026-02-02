package com.btc.collector.service;

import com.btc.collector.persistence.Candle15mRepository;
import com.btc.collector.persistence.HistoricalPatternRepository;
import com.btc.collector.persistence.Indicator15mRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DatabaseClearService {

    private final Candle15mRepository candleRepository;
    private final Indicator15mRepository indicatorRepository;
    private final HistoricalPatternRepository patternRepository;

    // Self-injection for separate transactions
    @Autowired
    @Lazy
    private DatabaseClearService self;

    /**
     * Clear all data from the database.
     * Each step commits separately - if one fails, previous deletes are preserved.
     *
     * Order: patterns -> indicators -> candles
     */
    public ClearResult clearAllData() {
        log.warn("=== CLEARING ALL DATABASE DATA ===");

        // Get counts before deletion
        long candleCount = candleRepository.count();
        long indicatorCount = indicatorRepository.count();
        long patternCount = patternRepository.countPatterns();

        log.info("Before clear: {} candles, {} indicators, {} patterns",
                candleCount, indicatorCount, patternCount);

        // Step 1: Delete patterns (separate transaction, commits immediately)
        log.info("[Step 1/3] Deleting {} patterns...", patternCount);
        self.deleteAllPatternsInTransaction();
        log.info("[Step 1/3] Patterns deleted and committed");

        // Step 2: Delete indicators (separate transaction, commits immediately)
        log.info("[Step 2/3] Deleting {} indicators...", indicatorCount);
        self.deleteAllIndicatorsInTransaction();
        log.info("[Step 2/3] Indicators deleted and committed");

        // Step 3: Delete candles (separate transaction, commits immediately)
        log.info("[Step 3/3] Deleting {} candles...", candleCount);
        self.deleteAllCandlesInTransaction();
        log.info("[Step 3/3] Candles deleted and committed");

        log.warn("=== DATABASE CLEARED ===");

        return new ClearResult(candleCount, indicatorCount, patternCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteAllPatternsInTransaction() {
        patternRepository.deleteAllPatterns();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteAllIndicatorsInTransaction() {
        // Use bulk delete query - does NOT load entities into memory
        indicatorRepository.deleteAllIndicators();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteAllCandlesInTransaction() {
        // Use bulk delete query - does NOT load entities into memory
        candleRepository.deleteAllCandles();
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
    public ClearResult clearPatternsAndIndicators() {
        long indicatorCount = indicatorRepository.count();
        long patternCount = patternRepository.countPatterns();

        self.deleteAllPatternsInTransaction();
        self.deleteAllIndicatorsInTransaction();

        log.info("Cleared {} patterns and {} indicators", patternCount, indicatorCount);
        return new ClearResult(0, indicatorCount, patternCount);
    }

    public record ClearResult(long candlesDeleted, long indicatorsDeleted, long patternsDeleted) {
        public long total() {
            return candlesDeleted + indicatorsDeleted + patternsDeleted;
        }
    }
}
