package com.btc.collector.analysis;

import com.btc.collector.persistence.Candle15mEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

@Service
@Slf4j
public class IndicatorEngine {

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final int SCALE = 4;

    // Minimum candles needed for calculations
    private static final int MIN_CANDLES = 100;

    public MarketSnapshot calculate(List<Candle15mEntity> candles) {
        if (candles == null || candles.size() < MIN_CANDLES) {
            log.warn("Not enough candles for snapshot: {}", candles == null ? 0 : candles.size());
            return null;
        }

        Candle15mEntity current = candles.get(candles.size() - 1);

        // Calculate MACD data once
        MACDData macdData = calculateMACDData(candles);

        return MarketSnapshot.builder()
                .timestamp(current.getOpenTime())
                .price(current.getClosePrice())
                .ema50(calculateEMA(candles, 50))
                .ema200(calculateEMA(candles, 200))
                .rsi(calculateRSI(candles, 14))
                .macdLine(macdData != null ? macdData.macdLine() : null)
                .signalLine(macdData != null ? macdData.signalLine() : null)
                .macdHistogram(macdData != null ? macdData.histogram() : null)
                .volumeChangePct(calculateVolumeChangePct(candles, 20))
                .priceChange1h(calculatePriceChangePct(candles, 4))
                .priceChange4h(calculatePriceChangePct(candles, 16))
                .priceChange24h(calculatePriceChangePct(candles, 96))
                .build();
    }

    private BigDecimal calculateEMA(List<Candle15mEntity> candles, int period) {
        if (candles.size() < period) return null;

        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), MC);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);

        // Initial SMA
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(candles.get(i).getClosePrice());
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), MC);

        // Calculate EMA for remaining candles
        for (int i = period; i < candles.size(); i++) {
            BigDecimal price = candles.get(i).getClosePrice();
            ema = price.multiply(k).add(ema.multiply(oneMinusK));
        }

        return ema.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateRSI(List<Candle15mEntity> candles, int period) {
        if (candles.size() < period + 1) return null;

        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        // Initial average gain/loss
        for (int i = candles.size() - period; i < candles.size(); i++) {
            BigDecimal change = candles.get(i).getClosePrice()
                    .subtract(candles.get(i - 1).getClosePrice());

            if (change.compareTo(BigDecimal.ZERO) > 0) {
                avgGain = avgGain.add(change);
            } else {
                avgLoss = avgLoss.add(change.abs());
            }
        }

        avgGain = avgGain.divide(BigDecimal.valueOf(period), MC);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), MC);

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100).setScale(SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal rs = avgGain.divide(avgLoss, MC);
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), MC));

        return rsi.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Calculate all EMA values for a given period in one pass - O(n).
     * Returns array where index i corresponds to EMA at candle i (null for i < period).
     */
    private BigDecimal[] calculateEMASeries(List<Candle15mEntity> candles, int period) {
        BigDecimal[] emas = new BigDecimal[candles.size()];
        if (candles.size() < period) return emas;

        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), MC);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);

        // Initial SMA
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(candles.get(i).getClosePrice());
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), MC);
        emas[period - 1] = ema;

        // Calculate EMA for remaining candles
        for (int i = period; i < candles.size(); i++) {
            BigDecimal price = candles.get(i).getClosePrice();
            ema = price.multiply(k).add(ema.multiply(oneMinusK));
            emas[i] = ema;
        }

        return emas;
    }

    /**
     * MACD data holder for efficient calculation.
     */
    private record MACDData(BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram) {}

    /**
     * Calculate MACD, Signal Line, and Histogram in one pass - O(n).
     */
    private MACDData calculateMACDData(List<Candle15mEntity> candles) {
        if (candles.size() < 35) return null; // Need 26 + 9 candles

        // Calculate EMA12 and EMA26 series in O(n)
        BigDecimal[] ema12Series = calculateEMASeries(candles, 12);
        BigDecimal[] ema26Series = calculateEMASeries(candles, 26);

        // Calculate MACD series (EMA12 - EMA26) starting from index 25
        int macdStartIdx = 25; // First valid MACD at index 25 (26th candle)
        int macdCount = candles.size() - macdStartIdx;
        BigDecimal[] macdSeries = new BigDecimal[macdCount];

        for (int i = 0; i < macdCount; i++) {
            int idx = macdStartIdx + i;
            if (ema12Series[idx] != null && ema26Series[idx] != null) {
                macdSeries[i] = ema12Series[idx].subtract(ema26Series[idx]);
            }
        }

        // Current MACD line (last value)
        BigDecimal macdLine = macdSeries[macdSeries.length - 1];
        if (macdLine == null) return null;

        // Calculate Signal Line (9-period EMA of MACD) - O(macdCount)
        if (macdCount < 9) return null;

        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(10), MC);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);

        // Initial SMA of first 9 MACD values
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < 9; i++) {
            if (macdSeries[i] == null) return null;
            sum = sum.add(macdSeries[i]);
        }
        BigDecimal signal = sum.divide(BigDecimal.valueOf(9), MC);

        // Calculate EMA for remaining MACD values
        for (int i = 9; i < macdCount; i++) {
            if (macdSeries[i] == null) return null;
            signal = macdSeries[i].multiply(k).add(signal.multiply(oneMinusK));
        }

        BigDecimal signalLine = signal.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal histogram = macdLine.subtract(signalLine).setScale(SCALE, RoundingMode.HALF_UP);
        macdLine = macdLine.setScale(SCALE, RoundingMode.HALF_UP);

        return new MACDData(macdLine, signalLine, histogram);
    }

    private BigDecimal calculateMACD(List<Candle15mEntity> candles) {
        MACDData data = calculateMACDData(candles);
        return data != null ? data.macdLine() : null;
    }

    private BigDecimal calculateSignalLine(List<Candle15mEntity> candles) {
        MACDData data = calculateMACDData(candles);
        return data != null ? data.signalLine() : null;
    }

    private BigDecimal calculateMACDHistogram(List<Candle15mEntity> candles) {
        MACDData data = calculateMACDData(candles);
        return data != null ? data.histogram() : null;
    }

    private BigDecimal calculateVolumeChangePct(List<Candle15mEntity> candles, int avgPeriod) {
        if (candles.size() < avgPeriod + 1) return null;

        // Average volume over period (excluding current)
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = candles.size() - avgPeriod - 1; i < candles.size() - 1; i++) {
            sum = sum.add(candles.get(i).getVolume());
        }
        BigDecimal avgVolume = sum.divide(BigDecimal.valueOf(avgPeriod), MC);

        if (avgVolume.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        BigDecimal currentVolume = candles.get(candles.size() - 1).getVolume();
        BigDecimal changePct = currentVolume.subtract(avgVolume)
                .divide(avgVolume, MC)
                .multiply(BigDecimal.valueOf(100));

        return changePct.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePriceChangePct(List<Candle15mEntity> candles, int periodsBack) {
        if (candles.size() < periodsBack + 1) return null;

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClosePrice();
        BigDecimal pastPrice = candles.get(candles.size() - 1 - periodsBack).getClosePrice();

        if (pastPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        BigDecimal changePct = currentPrice.subtract(pastPrice)
                .divide(pastPrice, MC)
                .multiply(BigDecimal.valueOf(100));

        return changePct.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
