package com.btc.collector.persistence;

import com.btc.collector.strategy.MarketRegime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketRegimeRepository extends JpaRepository<MarketRegimeEntity, Long> {

    /**
     * Find the most recent regime detection.
     */
    Optional<MarketRegimeEntity> findTopByOrderByTimestampDesc();

    /**
     * Find regime at specific timestamp.
     */
    Optional<MarketRegimeEntity> findByTimestamp(LocalDateTime timestamp);

    /**
     * Find recent regime changes (last N entries).
     */
    @Query("SELECT r FROM MarketRegimeEntity r ORDER BY r.timestamp DESC LIMIT :limit")
    List<MarketRegimeEntity> findRecentRegimes(@Param("limit") int limit);

    /**
     * Find regimes between timestamps.
     */
    @Query("SELECT r FROM MarketRegimeEntity r WHERE r.timestamp >= :start AND r.timestamp <= :end ORDER BY r.timestamp ASC")
    List<MarketRegimeEntity> findRegimesBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Find regime changes (where regime differs from previous).
     */
    @Query(value = """
        SELECT r.* FROM market_regime r
        WHERE r.id IN (
            SELECT r1.id FROM market_regime r1
            LEFT JOIN market_regime r2 ON r2.timestamp = (
                SELECT MAX(r3.timestamp) FROM market_regime r3 WHERE r3.timestamp < r1.timestamp
            )
            WHERE r2.regime_type IS NULL OR r1.regime_type != r2.regime_type
        )
        ORDER BY r.timestamp DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<MarketRegimeEntity> findRegimeChanges(@Param("limit") int limit);

    /**
     * Count regimes by type in a time range.
     */
    @Query("SELECT r.regimeType, COUNT(r) FROM MarketRegimeEntity r WHERE r.timestamp >= :start GROUP BY r.regimeType")
    List<Object[]> countByRegimeTypeSince(@Param("start") LocalDateTime start);

    /**
     * Find last N regimes of specific type.
     */
    @Query("SELECT r FROM MarketRegimeEntity r WHERE r.regimeType = :type ORDER BY r.timestamp DESC LIMIT :limit")
    List<MarketRegimeEntity> findRecentByType(@Param("type") MarketRegime type, @Param("limit") int limit);
}
