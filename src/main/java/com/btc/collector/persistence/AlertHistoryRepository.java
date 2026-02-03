package com.btc.collector.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertHistoryRepository extends JpaRepository<AlertHistoryEntity, Long> {

    /**
     * Find alerts ready for evaluation (time passed and not yet evaluated).
     */
    @Query("SELECT a FROM AlertHistoryEntity a WHERE a.evaluated = false AND a.evaluateAt <= :now ORDER BY a.evaluateAt ASC")
    List<AlertHistoryEntity> findReadyForEvaluation(@Param("now") LocalDateTime now);

    /**
     * Find recent alerts ordered by time.
     */
    @Query("SELECT a FROM AlertHistoryEntity a ORDER BY a.alertTime DESC LIMIT :limit")
    List<AlertHistoryEntity> findRecentAlerts(@Param("limit") int limit);

    /**
     * Find alerts by strategy.
     */
    @Query("SELECT a FROM AlertHistoryEntity a WHERE a.strategyId = :strategyId ORDER BY a.alertTime DESC")
    List<AlertHistoryEntity> findByStrategyId(@Param("strategyId") String strategyId);

    /**
     * Count unevaluated alerts (excluding zero probability).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.evaluated = false AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countPendingEvaluations();

    /**
     * Count successful alerts (excluding zero probability).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.evaluated = true AND a.success = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countSuccessful();

    /**
     * Count failed alerts (excluding zero probability).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.evaluated = true AND a.success = false AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countFailed();

    /**
     * Get alerts within date range.
     */
    @Query("SELECT a FROM AlertHistoryEntity a WHERE a.alertTime >= :startTime AND a.alertTime <= :endTime ORDER BY a.alertTime DESC")
    List<AlertHistoryEntity> findAlertsBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * Get overall success rate (excluding zero probability).
     */
    @Query("SELECT COALESCE(AVG(CASE WHEN a.success = true THEN 1.0 ELSE 0.0 END) * 100, 0) FROM AlertHistoryEntity a WHERE a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    Double getOverallSuccessRate();

    /**
     * Count total signals (excluding zero probability for statistics).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.finalProbability IS NULL OR a.finalProbability > 0")
    long countTotal();

    /**
     * Count signals sent to Telegram.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true")
    long countSentToTelegram();

    /**
     * Count signals with zero probability (ignored).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.finalProbability = 0")
    long countIgnoredZeroProb();

    /**
     * Find alerts by snapshot group ID.
     */
    @Query("SELECT a FROM AlertHistoryEntity a WHERE a.snapshotGroupId = :snapshotId ORDER BY a.finalProbability DESC")
    List<AlertHistoryEntity> findBySnapshotGroupId(@Param("snapshotId") String snapshotId);

    /**
     * Count distinct snapshot groups.
     */
    @Query("SELECT COUNT(DISTINCT a.snapshotGroupId) FROM AlertHistoryEntity a WHERE a.snapshotGroupId IS NOT NULL")
    long countSnapshotGroups();

    /**
     * Get recent snapshot group IDs.
     */
    @Query("SELECT DISTINCT a.snapshotGroupId FROM AlertHistoryEntity a WHERE a.snapshotGroupId IS NOT NULL ORDER BY a.alertTime DESC LIMIT :limit")
    List<String> findRecentSnapshotGroupIds(@Param("limit") int limit);
}
