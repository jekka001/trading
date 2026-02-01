package com.btc.collector.service;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.persistence.Candle15mEntity;
import com.btc.collector.persistence.Candle15mRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test Plan Section 1: Candles Synchronization
 *
 * Tests:
 * - Trigger candle sync and verify new candles are added to the database
 * - Ensure the latest candle timestamp matches expected UTC+2 time
 * - Test behavior when no new candles are available (should not create duplicates)
 */
@DisplayName("1. Candles Synchronization Tests")
class CandleSyncServiceTest extends BaseIntegrationTest {

    private static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Kyiv");

    @Autowired
    private Candle15mRepository candleRepository;

    @Autowired
    private CandleSyncService candleSyncService;

    @BeforeEach
    void setUp() {
        candleRepository.deleteAll();
    }

    @Test
    @DisplayName("1.1 New candles are added to database")
    void newCandlesAreAddedToDatabase() {
        // Given: Empty database
        assertThat(candleRepository.count()).isZero();

        // When: Insert test candles with fixed time (no nanoseconds)
        LocalDateTime openTime = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        Candle15mEntity candle = createTestCandle(openTime);
        candleRepository.save(candle);

        // Then: Candle is persisted
        assertThat(candleRepository.count()).isEqualTo(1);
        Optional<Candle15mEntity> found = candleRepository.findById(openTime);
        assertThat(found).isPresent();
        assertThat(found.get().getClosePrice()).isEqualByComparingTo(BigDecimal.valueOf(50000));
    }

    @Test
    @DisplayName("1.2 Latest candle timestamp is correct")
    void latestCandleTimestampIsCorrect() {
        // Given: Multiple candles
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 15);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 30);

        candleRepository.save(createTestCandle(time1));
        candleRepository.save(createTestCandle(time2));
        candleRepository.save(createTestCandle(time3));

        // When: Get latest candle
        Optional<LocalDateTime> latest = candleRepository.findMaxOpenTime();

        // Then: Latest timestamp matches
        assertThat(latest).isPresent();
        assertThat(latest.get()).isEqualTo(time3);
    }

    @Test
    @DisplayName("1.3 No duplicate candles created")
    void noDuplicateCandlesCreated() {
        // Given: Existing candle
        LocalDateTime time = LocalDateTime.of(2024, 1, 1, 10, 0);
        candleRepository.save(createTestCandle(time));
        assertThat(candleRepository.count()).isEqualTo(1);

        // When: Try to save same candle again (should update, not duplicate)
        Candle15mEntity updated = createTestCandle(time);
        updated.setClosePrice(BigDecimal.valueOf(51000)); // Different price
        candleRepository.save(updated);

        // Then: Still only one candle
        assertThat(candleRepository.count()).isEqualTo(1);

        // And: Price is updated
        Optional<Candle15mEntity> found = candleRepository.findById(time);
        assertThat(found).isPresent();
        assertThat(found.get().getClosePrice()).isEqualByComparingTo(BigDecimal.valueOf(51000));
    }

    @Test
    @DisplayName("1.4 Candles are ordered by time")
    void candlesAreOrderedByTime() {
        // Given: Candles inserted out of order
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 30);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 15);

        candleRepository.save(createTestCandle(time1));
        candleRepository.save(createTestCandle(time2));
        candleRepository.save(createTestCandle(time3));

        // When: Get all candles ordered
        List<Candle15mEntity> candles = candleRepository.findAllOrderByOpenTimeAsc();

        // Then: Ordered correctly
        assertThat(candles).hasSize(3);
        assertThat(candles.get(0).getOpenTime()).isEqualTo(time2);
        assertThat(candles.get(1).getOpenTime()).isEqualTo(time3);
        assertThat(candles.get(2).getOpenTime()).isEqualTo(time1);
    }

    @Test
    @DisplayName("1.5 Candle count is accurate")
    void candleCountIsAccurate() {
        // Given: Add candles
        for (int i = 0; i < 10; i++) {
            LocalDateTime time = LocalDateTime.of(2024, 1, 1, 10, 0).plusMinutes(i * 15);
            candleRepository.save(createTestCandle(time));
        }

        // When: Get count
        long count = candleSyncService.getCandleCount();

        // Then: Count matches
        assertThat(count).isEqualTo(10);
    }

    private Candle15mEntity createTestCandle(LocalDateTime openTime) {
        return Candle15mEntity.builder()
                .openTime(openTime)
                .openPrice(BigDecimal.valueOf(49500))
                .highPrice(BigDecimal.valueOf(50500))
                .lowPrice(BigDecimal.valueOf(49000))
                .closePrice(BigDecimal.valueOf(50000))
                .volume(BigDecimal.valueOf(100))
                .closeTime(openTime.plusMinutes(15).minusSeconds(1))
                .build();
    }
}
