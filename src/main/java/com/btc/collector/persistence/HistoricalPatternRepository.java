package com.btc.collector.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface HistoricalPatternRepository extends JpaRepository<HistoricalPatternEntity, Long> {

    /**
     * Find pattern by candle time.
     */
    Optional<HistoricalPatternEntity> findByCandleTime(LocalDateTime candleTime);

    /**
     * Check if pattern exists for a given candle time.
     */
    boolean existsByCandleTime(LocalDateTime candleTime);

    /**
     * Find all patterns within a date range.
     */
    @Query("SELECT p FROM HistoricalPatternEntity p WHERE p.candleTime >= :startTime ORDER BY p.candleTime ASC")
    List<HistoricalPatternEntity> findPatternsSince(@Param("startTime") LocalDateTime startTime);

    /**
     * Find patterns by strategy ID.
     */
    @Query("SELECT p FROM HistoricalPatternEntity p WHERE p.strategyId = :strategyId ORDER BY p.candleTime DESC")
    List<HistoricalPatternEntity> findByStrategyId(@Param("strategyId") String strategyId);

    /**
     * Find recent patterns (last N days).
     */
    @Query("SELECT p FROM HistoricalPatternEntity p WHERE p.candleTime >= :since ORDER BY p.candleTime ASC")
    List<HistoricalPatternEntity> findRecentPatterns(@Param("since") LocalDateTime since);

    /**
     * Get the latest candle time that has a pattern.
     */
    @Query("SELECT MAX(p.candleTime) FROM HistoricalPatternEntity p")
    Optional<LocalDateTime> findMaxCandleTime();

    /**
     * Get the earliest candle time that has a pattern.
     */
    @Query("SELECT MIN(p.candleTime) FROM HistoricalPatternEntity p")
    Optional<LocalDateTime> findMinCandleTime();

    /**
     * Count patterns.
     */
    @Query("SELECT COUNT(p) FROM HistoricalPatternEntity p")
    long countPatterns();

    /**
     * Delete all patterns (for full rebuild).
     */
    @Modifying
    @Query("DELETE FROM HistoricalPatternEntity p")
    void deleteAllPatterns();

    /**
     * Find all patterns ordered by candle time.
     */
    @Query("SELECT p FROM HistoricalPatternEntity p ORDER BY p.candleTime ASC")
    List<HistoricalPatternEntity> findAllOrderByCandleTimeAsc();

    /**
     * Find unevaluated patterns that can be evaluated (have enough future data).
     * Pattern can be evaluated if candle_time + 24h <= now
     */
    @Query("SELECT p FROM HistoricalPatternEntity p WHERE p.evaluated = false AND p.candleTime <= :maxTime ORDER BY p.candleTime ASC")
    List<HistoricalPatternEntity> findUnevaluatedPatternsBefore(@Param("maxTime") LocalDateTime maxTime);

    /**
     * Get the latest evaluated pattern time.
     */
    @Query("SELECT MAX(p.candleTime) FROM HistoricalPatternEntity p WHERE p.evaluated = true")
    Optional<LocalDateTime> findMaxEvaluatedCandleTime();

    /**
     * Count unevaluated patterns.
     */
    @Query("SELECT COUNT(p) FROM HistoricalPatternEntity p WHERE p.evaluated = false")
    long countUnevaluated();

    /**
     * Count evaluated patterns.
     */
    @Query("SELECT COUNT(p) FROM HistoricalPatternEntity p WHERE p.evaluated = true")
    long countEvaluated();
}
