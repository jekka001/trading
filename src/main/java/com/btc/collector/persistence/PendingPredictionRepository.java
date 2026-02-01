package com.btc.collector.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PendingPredictionRepository extends JpaRepository<PendingPredictionEntity, Long> {

    @Query("SELECT p FROM PendingPredictionEntity p WHERE p.evaluated = false AND p.evaluateAt <= :now")
    List<PendingPredictionEntity> findReadyForEvaluation(@Param("now") LocalDateTime now);

    @Query("SELECT p FROM PendingPredictionEntity p WHERE p.strategyId = :strategyId AND p.evaluated = false")
    List<PendingPredictionEntity> findPendingByStrategy(@Param("strategyId") String strategyId);

    long countByEvaluatedFalse();
}
