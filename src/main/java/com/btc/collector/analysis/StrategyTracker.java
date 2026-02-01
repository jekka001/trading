package com.btc.collector.analysis;

import com.btc.collector.persistence.StrategyStatsEntity;
import com.btc.collector.persistence.StrategyStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tracks strategy performance and calculates weights.
 * Uses database persistence for long-term learning continuity across restarts.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyTracker {

    private final StrategyStatsRepository repository;

    /**
     * Generate strategy ID from market snapshot.
     * Format: RSI_{LOW|MID|HIGH}_EMA_{BULL|BEAR}_VOL_{LOW|MED|HIGH}
     */
    public String generateStrategyId(MarketSnapshot snapshot) {
        if (snapshot == null || snapshot.getRsi() == null) {
            return "UNKNOWN";
        }

        StringBuilder sb = new StringBuilder();

        // RSI bucket
        double rsi = snapshot.getRsi().doubleValue();
        if (rsi < 30) {
            sb.append("RSI_LOW");
        } else if (rsi > 70) {
            sb.append("RSI_HIGH");
        } else {
            sb.append("RSI_MID");
        }

        // EMA trend
        sb.append("_EMA_");
        sb.append(snapshot.isEma50BelowEma200() ? "BEAR" : "BULL");

        // Volume bucket (MEDIUM -> MED for consistency)
        sb.append("_VOL_");
        MarketSnapshot.VolumeBucket vol = snapshot.getVolumeBucket();
        String volName = vol == MarketSnapshot.VolumeBucket.MEDIUM ? "MED" : vol.name();
        sb.append(volName);

        return sb.toString();
    }

    /**
     * Get or create strategy stats.
     */
    @Transactional
    public StrategyStats getOrCreate(String strategyId) {
        StrategyStatsEntity entity = repository.findById(strategyId)
                .orElseGet(() -> {
                    StrategyStatsEntity newEntity = StrategyStatsEntity.createNew(strategyId);
                    return repository.save(newEntity);
                });
        return toDto(entity);
    }

    /**
     * Get strategy stats if exists.
     */
    public Optional<StrategyStats> get(String strategyId) {
        return repository.findById(strategyId).map(this::toDto);
    }

    /**
     * Record successful prediction for strategy.
     */
    @Transactional
    public void recordSuccess(String strategyId) {
        StrategyStatsEntity entity = repository.findById(strategyId)
                .orElseGet(() -> StrategyStatsEntity.createNew(strategyId));
        entity.recordSuccess();
        repository.save(entity);
        log.info("Strategy {} success recorded. Rate: {}%, Weight: {}",
                strategyId, entity.getSuccessRate(), entity.getWeight());
    }

    /**
     * Record failed prediction for strategy.
     */
    @Transactional
    public void recordFailure(String strategyId) {
        StrategyStatsEntity entity = repository.findById(strategyId)
                .orElseGet(() -> StrategyStatsEntity.createNew(strategyId));
        entity.recordFailure();
        repository.save(entity);
        log.info("Strategy {} failure recorded. Rate: {}%, Weight: {}",
                strategyId, entity.getSuccessRate(), entity.getWeight());
    }

    /**
     * Get weight for strategy. Returns 0.5 for unknown strategies.
     */
    public BigDecimal getWeight(String strategyId) {
        return repository.findById(strategyId)
                .map(StrategyStatsEntity::getWeight)
                .orElse(BigDecimal.valueOf(0.5));
    }

    /**
     * Check if strategy needs degradation alert.
     */
    public boolean needsDegradationAlert(String strategyId) {
        return repository.findById(strategyId)
                .map(StrategyStatsEntity::needsDegradationAlert)
                .orElse(false);
    }

    /**
     * Mark strategy degradation as alerted.
     */
    @Transactional
    public void markDegradationAlerted(String strategyId) {
        repository.findById(strategyId).ifPresent(entity -> {
            entity.setDegradationAlerted(true);
            repository.save(entity);
        });
    }

    /**
     * Get all tracked strategies.
     */
    public Collection<StrategyStats> getAllStrategies() {
        return repository.findAllOrderBySuccessRateDesc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Get count of tracked strategies.
     */
    public int getStrategyCount() {
        return (int) repository.count();
    }

    /**
     * Convert entity to DTO for external use.
     */
    private StrategyStats toDto(StrategyStatsEntity entity) {
        return StrategyStats.builder()
                .strategyId(entity.getStrategyName())
                .totalPredictions(entity.getTotalPredictions())
                .successfulPredictions(entity.getSuccessfulPredictions())
                .failedPredictions(entity.getFailedPredictions())
                .successRate(entity.getSuccessRate())
                .score(entity.getScore())
                .weight(entity.getWeight())
                .lastUpdated(entity.getLastUpdated())
                .degradationAlerted(entity.isDegradationAlerted())
                .build();
    }
}
