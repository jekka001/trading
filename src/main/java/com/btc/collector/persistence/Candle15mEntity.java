package com.btc.collector.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "btc_candle_15m")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle15mEntity {

    @Id
    @Column(name = "open_time")
    private LocalDateTime openTime;

    @Column(name = "open_price", precision = 18, scale = 8, nullable = false)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 18, scale = 8, nullable = false)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 18, scale = 8, nullable = false)
    private BigDecimal lowPrice;

    @Column(name = "close_price", precision = 18, scale = 8, nullable = false)
    private BigDecimal closePrice;

    @Column(name = "volume", precision = 18, scale = 8, nullable = false)
    private BigDecimal volume;

    @Column(name = "close_time", nullable = false)
    private LocalDateTime closeTime;
}
