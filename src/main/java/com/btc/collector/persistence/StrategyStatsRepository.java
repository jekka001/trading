package com.btc.collector.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StrategyStatsRepository extends JpaRepository<StrategyStatsEntity, String> {

    @Query("SELECT s FROM StrategyStatsEntity s WHERE s.degradationAlerted = false AND s.weight <= 0.05")
    List<StrategyStatsEntity> findDegradedNotAlerted();

    @Query("SELECT s FROM StrategyStatsEntity s ORDER BY s.successRate DESC")
    List<StrategyStatsEntity> findAllOrderBySuccessRateDesc();
}
