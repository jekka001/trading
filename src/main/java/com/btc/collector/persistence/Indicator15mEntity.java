package com.btc.collector.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "btc_indicator_15m")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Indicator15mEntity {

    @Id
    @Column(name = "open_time")
    private LocalDateTime openTime;

    @Column(name = "ema_50", precision = 18, scale = 8)
    private BigDecimal ema50;

    @Column(name = "ema_200", precision = 18, scale = 8)
    private BigDecimal ema200;

    @Column(name = "rsi_14", precision = 10, scale = 4)
    private BigDecimal rsi14;

    @Column(name = "atr_14", precision = 18, scale = 8)
    private BigDecimal atr14;

    @Column(name = "bb_upper", precision = 18, scale = 8)
    private BigDecimal bbUpper;

    @Column(name = "bb_middle", precision = 18, scale = 8)
    private BigDecimal bbMiddle;

    @Column(name = "bb_lower", precision = 18, scale = 8)
    private BigDecimal bbLower;

    @Column(name = "avg_volume_20", precision = 18, scale = 8)
    private BigDecimal avgVolume20;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
