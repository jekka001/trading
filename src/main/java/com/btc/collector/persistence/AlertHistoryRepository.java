package com.btc.collector.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
     * Find alerts ready for confidence evaluation (evaluated but not confidence evaluated, with probability > 0).
     */
    @Query("SELECT a FROM AlertHistoryEntity a WHERE a.evaluated = true AND a.confidenceEvaluated = false AND (a.finalProbability IS NULL OR a.finalProbability > 0) ORDER BY a.evaluatedAt ASC")
    List<AlertHistoryEntity> findReadyForConfidenceEvaluation();

    /**
     * Count alerts pending confidence evaluation.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.evaluated = true AND a.confidenceEvaluated = false AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countPendingConfidenceEvaluation();

    /**
     * Sum of all positive confidence scores (global).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.confidenceEvaluated = true AND a.confidenceScore > 0")
    long countConfidencePositive();

    /**
     * Sum of all negative confidence scores (global).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.confidenceEvaluated = true AND a.confidenceScore < 0")
    long countConfidenceNegative();

    /**
     * Sum of all neutral confidence scores (global).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.confidenceEvaluated = true AND a.confidenceScore = 0")
    long countConfidenceNeutral();

    /**
     * Total confidence score (sum of all scores).
     */
    @Query("SELECT COALESCE(SUM(a.confidenceScore), 0) FROM AlertHistoryEntity a WHERE a.confidenceEvaluated = true")
    long sumConfidenceScore();

    /**
     * Count total confidence evaluated alerts.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.confidenceEvaluated = true")
    long countConfidenceEvaluated();

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

    // ==================== Profit Evaluation Queries ====================

    /**
     * Find alerts ready for profit evaluation (evaluated but actualProfitUsd not set, with probability > 0).
     */
    @Query("SELECT a FROM AlertHistoryEntity a WHERE a.evaluated = true AND a.actualProfitUsd IS NULL AND (a.finalProbability IS NULL OR a.finalProbability > 0) ORDER BY a.evaluatedAt ASC")
    List<AlertHistoryEntity> findReadyForProfitEvaluation();

    /**
     * Count alerts pending profit evaluation.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.evaluated = true AND a.actualProfitUsd IS NULL AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countPendingProfitEvaluation();

    /**
     * Sum of all actual profit USD (global PnL).
     */
    @Query("SELECT COALESCE(SUM(a.actualProfitUsd), 0) FROM AlertHistoryEntity a WHERE a.actualProfitUsd IS NOT NULL")
    BigDecimal sumActualProfitUsd();

    /**
     * Average actual profit percentage (global).
     */
    @Query("SELECT AVG(a.actualProfitPct) FROM AlertHistoryEntity a WHERE a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    BigDecimal avgActualProfitPct();

    /**
     * Count trades evaluated for profit.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.actualProfitUsd IS NOT NULL")
    long countProfitEvaluated();

    // ==================== Telegram-Only Queries ====================
    // All queries filter by: sentToTelegram=true, probability>0, evaluated=true (non-pending)

    /**
     * Count Telegram alerts that have been evaluated (non-pending).
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countTelegramEvaluated();

    /**
     * Count successful Telegram alerts.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.evaluated = true AND a.success = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countTelegramSuccessful();

    /**
     * Count failed Telegram alerts.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.evaluated = true AND a.success = false AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countTelegramFailed();

    /**
     * Get success rate for Telegram alerts only.
     */
    @Query("SELECT COALESCE(AVG(CASE WHEN a.success = true THEN 1.0 ELSE 0.0 END) * 100, 0) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    Double getTelegramSuccessRate();

    /**
     * Count positive confidence scores for Telegram alerts.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.confidenceEvaluated = true AND a.confidenceScore > 0")
    long countTelegramConfidencePositive();

    /**
     * Count negative confidence scores for Telegram alerts.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.confidenceEvaluated = true AND a.confidenceScore < 0")
    long countTelegramConfidenceNegative();

    /**
     * Sum confidence score for Telegram alerts.
     */
    @Query("SELECT COALESCE(SUM(a.confidenceScore), 0) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.confidenceEvaluated = true")
    long sumTelegramConfidenceScore();

    /**
     * Count confidence evaluated Telegram alerts.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.confidenceEvaluated = true")
    long countTelegramConfidenceEvaluated();

    /**
     * Sum actual profit USD for Telegram alerts only.
     */
    @Query("SELECT COALESCE(SUM(a.actualProfitUsd), 0) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.actualProfitUsd IS NOT NULL")
    BigDecimal sumTelegramActualProfitUsd();

    /**
     * Average actual profit percentage for Telegram alerts only.
     */
    @Query("SELECT AVG(a.actualProfitPct) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    BigDecimal avgTelegramActualProfitPct();

    /**
     * Count Telegram trades evaluated for profit.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.actualProfitUsd IS NOT NULL")
    long countTelegramProfitEvaluated();

    /**
     * Get distinct strategy IDs that have Telegram alerts.
     */
    @Query("SELECT DISTINCT a.strategyId FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    List<String> findTelegramStrategyIds();

    /**
     * Count Telegram alerts by strategy.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.strategyId = :strategyId AND a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countTelegramByStrategy(@Param("strategyId") String strategyId);

    /**
     * Count successful Telegram alerts by strategy.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.strategyId = :strategyId AND a.evaluated = true AND a.success = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countTelegramSuccessfulByStrategy(@Param("strategyId") String strategyId);

    /**
     * Count failed Telegram alerts by strategy.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.strategyId = :strategyId AND a.evaluated = true AND a.success = false AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    long countTelegramFailedByStrategy(@Param("strategyId") String strategyId);

    /**
     * Count positive confidence scores for Telegram alerts by strategy.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.strategyId = :strategyId AND a.confidenceEvaluated = true AND a.confidenceScore > 0")
    long countTelegramConfidencePositiveByStrategy(@Param("strategyId") String strategyId);

    /**
     * Count negative confidence scores for Telegram alerts by strategy.
     */
    @Query("SELECT COUNT(a) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.strategyId = :strategyId AND a.confidenceEvaluated = true AND a.confidenceScore < 0")
    long countTelegramConfidenceNegativeByStrategy(@Param("strategyId") String strategyId);

    /**
     * Sum actual profit USD for Telegram alerts by strategy.
     */
    @Query("SELECT COALESCE(SUM(a.actualProfitUsd), 0) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.strategyId = :strategyId AND a.actualProfitUsd IS NOT NULL")
    BigDecimal sumTelegramActualProfitUsdByStrategy(@Param("strategyId") String strategyId);

    /**
     * Average actual profit percentage for Telegram alerts by strategy.
     */
    @Query("SELECT AVG(a.actualProfitPct) FROM AlertHistoryEntity a WHERE a.sentToTelegram = true AND a.strategyId = :strategyId AND a.evaluated = true AND (a.finalProbability IS NULL OR a.finalProbability > 0)")
    BigDecimal avgTelegramActualProfitPctByStrategy(@Param("strategyId") String strategyId);
}
