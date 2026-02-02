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
public interface Indicator15mRepository extends JpaRepository<Indicator15mEntity, LocalDateTime> {

    @Query("SELECT MAX(i.openTime) FROM Indicator15mEntity i")
    Optional<LocalDateTime> findMaxOpenTime();

    @Query("SELECT COUNT(i) FROM Indicator15mEntity i")
    long countIndicators();

    Optional<Indicator15mEntity> findTopByOrderByOpenTimeDesc();

    @Query("SELECT i FROM Indicator15mEntity i WHERE i.openTime <= :timestamp ORDER BY i.openTime DESC LIMIT :limit")
    List<Indicator15mEntity> findLastNIndicators(@Param("timestamp") LocalDateTime timestamp, @Param("limit") int limit);

    @Query("SELECT i FROM Indicator15mEntity i ORDER BY i.openTime DESC LIMIT :limit")
    List<Indicator15mEntity> findRecentIndicators(@Param("limit") int limit);

    @Query("SELECT i FROM Indicator15mEntity i WHERE i.openTime >= :startTime AND i.openTime <= :endTime ORDER BY i.openTime ASC")
    List<Indicator15mEntity> findIndicatorsBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    Optional<Indicator15mEntity> findByOpenTime(LocalDateTime openTime);

    /**
     * Find indicators after a given time, ordered ascending, with limit for batch processing.
     */
    @Query("SELECT i FROM Indicator15mEntity i WHERE i.openTime > :afterTime ORDER BY i.openTime ASC LIMIT :limit")
    List<Indicator15mEntity> findIndicatorsAfter(@Param("afterTime") LocalDateTime afterTime, @Param("limit") int limit);

    /**
     * Count indicators after a given time.
     */
    @Query("SELECT COUNT(i) FROM Indicator15mEntity i WHERE i.openTime > :afterTime")
    long countIndicatorsAfter(@Param("afterTime") LocalDateTime afterTime);

    /**
     * Find indicators since a given time (inclusive), ordered ascending.
     */
    @Query("SELECT i FROM Indicator15mEntity i WHERE i.openTime >= :sinceTime ORDER BY i.openTime ASC")
    List<Indicator15mEntity> findIndicatorsSince(@Param("sinceTime") LocalDateTime sinceTime);

    /**
     * Bulk delete all indicators without loading into memory.
     */
    @Modifying
    @Query("DELETE FROM Indicator15mEntity")
    void deleteAllIndicators();
}
