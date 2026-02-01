package com.btc.collector.persistence;

import com.btc.collector.strategy.MarketRegime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persisted market regime detection result.
 * Stored every 15 minutes aligned with candle close.
 */
@Entity
@Table(name = "market_regime", indexes = {
    @Index(name = "idx_regime_timestamp", columnList = "timestamp"),
    @Index(name = "idx_regime_type", columnList = "regime_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRegimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Timestamp when regime was detected (aligned with candle close).
     */
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    /**
     * Detected regime type.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "regime_type", nullable = false, length = 20)
    private MarketRegime regimeType;

    /**
     * Confidence score (0.0 - 1.0).
     */
    @Column(name = "confidence", nullable = false)
    private Double confidence;

    /**
     * Number of conditions matched.
     */
    @Column(name = "matched_conditions")
    private Integer matchedConditions;

    /**
     * Total conditions checked.
     */
    @Column(name = "total_conditions")
    private Integer totalConditions;
}
