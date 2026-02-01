package com.btc.collector.indicator;

import com.btc.collector.persistence.Candle15mEntity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public class IndicatorCalculator {

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final int SCALE = 8;

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private IndicatorCalculator() {}

    /**
     * BigDecimal square root using Newton-Raphson method
     */
    private static BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal x = BigDecimal.valueOf(Math.sqrt(value.doubleValue()));

        // Newton-Raphson iterations for precision
        for (int i = 0; i < 10; i++) {
            x = x.add(value.divide(x, MC)).divide(TWO, MC);
        }

        return x.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * EMA = price * k + EMA_prev * (1 - k)
     * k = 2 / (period + 1)
     */
    public static BigDecimal calculateEMA(List<Candle15mEntity> candles, int period, BigDecimal previousEma) {
        if (candles.size() < period) {
            return null;
        }

        BigDecimal k = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1), MC);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);

        if (previousEma == null) {
            // Calculate SMA for initial EMA
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = candles.size() - period; i < candles.size(); i++) {
                sum = sum.add(candles.get(i).getClosePrice());
            }
            previousEma = sum.divide(BigDecimal.valueOf(period), MC);
        }

        Candle15mEntity lastCandle = candles.get(candles.size() - 1);
        return lastCandle.getClosePrice().multiply(k)
                .add(previousEma.multiply(oneMinusK))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * RSI = 100 - (100 / (1 + RS))
     * RS = Average Gain / Average Loss
     */
    public static BigDecimal calculateRSI(List<Candle15mEntity> candles, int period) {
        if (candles.size() < period + 1) {
            return null;
        }

        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        // Calculate initial average gain/loss
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
            return BigDecimal.valueOf(100).setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal rs = avgGain.divide(avgLoss, MC);
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), MC));

        return rsi.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * ATR = SMA of True Range
     * True Range = max(high - low, |high - prev_close|, |low - prev_close|)
     */
    public static BigDecimal calculateATR(List<Candle15mEntity> candles, int period) {
        if (candles.size() < period + 1) {
            return null;
        }

        BigDecimal sum = BigDecimal.ZERO;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            Candle15mEntity current = candles.get(i);
            Candle15mEntity previous = candles.get(i - 1);

            BigDecimal highLow = current.getHighPrice().subtract(current.getLowPrice());
            BigDecimal highPrevClose = current.getHighPrice().subtract(previous.getClosePrice()).abs();
            BigDecimal lowPrevClose = current.getLowPrice().subtract(previous.getClosePrice()).abs();

            BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);
            sum = sum.add(trueRange);
        }

        return sum.divide(BigDecimal.valueOf(period), MC).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Bollinger Bands
     * Middle = SMA(period)
     * Upper = Middle + (stdDev * multiplier)
     * Lower = Middle - (stdDev * multiplier)
     */
    public static BigDecimal[] calculateBollingerBands(List<Candle15mEntity> candles, int period, double multiplier) {
        if (candles.size() < period) {
            return null;
        }

        // Calculate SMA (middle band)
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            sum = sum.add(candles.get(i).getClosePrice());
        }
        BigDecimal middle = sum.divide(BigDecimal.valueOf(period), MC);

        // Calculate standard deviation
        BigDecimal varianceSum = BigDecimal.ZERO;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            BigDecimal diff = candles.get(i).getClosePrice().subtract(middle);
            varianceSum = varianceSum.add(diff.multiply(diff));
        }
        BigDecimal variance = varianceSum.divide(BigDecimal.valueOf(period), MC);
        BigDecimal stdDev = sqrt(variance);

        BigDecimal deviation = stdDev.multiply(BigDecimal.valueOf(multiplier));
        BigDecimal upper = middle.add(deviation).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal lower = middle.subtract(deviation).setScale(SCALE, RoundingMode.HALF_UP);

        return new BigDecimal[]{
                upper,
                middle.setScale(SCALE, RoundingMode.HALF_UP),
                lower
        };
    }

    /**
     * Average Volume = SMA of volume
     */
    public static BigDecimal calculateAvgVolume(List<Candle15mEntity> candles, int period) {
        if (candles.size() < period) {
            return null;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            sum = sum.add(candles.get(i).getVolume());
        }

        return sum.divide(BigDecimal.valueOf(period), MC).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
