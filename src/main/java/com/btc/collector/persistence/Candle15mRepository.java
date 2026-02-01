package com.btc.collector.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface Candle15mRepository extends JpaRepository<Candle15mEntity, LocalDateTime> {

    @Query("SELECT MAX(c.openTime) FROM Candle15mEntity c")
    Optional<LocalDateTime> findMaxOpenTime();

    @Query("SELECT MIN(c.openTime) FROM Candle15mEntity c")
    Optional<LocalDateTime> findMinOpenTime();

    boolean existsByOpenTime(LocalDateTime openTime);

    @Query("SELECT c FROM Candle15mEntity c WHERE c.openTime <= :openTime ORDER BY c.openTime DESC LIMIT :limit")
    List<Candle15mEntity> findLastNCandles(@Param("openTime") LocalDateTime openTime, @Param("limit") int limit);

    @Query("SELECT c FROM Candle15mEntity c WHERE c.openTime > :afterTime ORDER BY c.openTime ASC")
    List<Candle15mEntity> findCandlesAfter(@Param("afterTime") LocalDateTime afterTime);

    @Query("SELECT c FROM Candle15mEntity c ORDER BY c.openTime ASC")
    List<Candle15mEntity> findAllOrderByOpenTimeAsc();

    @Query("SELECT c FROM Candle15mEntity c ORDER BY c.openTime DESC LIMIT :limit")
    List<Candle15mEntity> findRecentCandles(@Param("limit") int limit);

    @Query("SELECT MAX(c.highPrice) FROM Candle15mEntity c WHERE c.openTime >= :startTime AND c.openTime <= :endTime")
    java.math.BigDecimal findMaxHighPriceBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * Find candles in a time range, ordered ascending.
     */
    @Query("SELECT c FROM Candle15mEntity c WHERE c.openTime >= :startTime AND c.openTime <= :endTime ORDER BY c.openTime ASC")
    List<Candle15mEntity> findCandlesBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}
