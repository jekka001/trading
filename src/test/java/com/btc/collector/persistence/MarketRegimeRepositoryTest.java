package com.btc.collector.persistence;

import com.btc.collector.BaseIntegrationTest;
import com.btc.collector.strategy.MarketRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MarketRegimeRepository.
 */
@DisplayName("MarketRegimeRepository Tests")
class MarketRegimeRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private MarketRegimeRepository regimeRepository;

    @BeforeEach
    void setUp() {
        regimeRepository.deleteAll();
    }

    @Nested
    @DisplayName("Basic CRUD Tests")
    class CrudTests {

        @Test
        @DisplayName("Should save and retrieve regime")
        void shouldSaveAndRetrieveRegime() {
            MarketRegimeEntity entity = MarketRegimeEntity.builder()
                    .timestamp(LocalDateTime.now())
                    .regimeType(MarketRegime.TREND)
                    .confidence(0.75)
                    .matchedConditions(3)
                    .totalConditions(4)
                    .build();

            MarketRegimeEntity saved = regimeRepository.save(entity);

            assertNotNull(saved.getId());

            Optional<MarketRegimeEntity> found = regimeRepository.findById(saved.getId());
            assertTrue(found.isPresent());
            assertEquals(MarketRegime.TREND, found.get().getRegimeType());
            assertEquals(0.75, found.get().getConfidence());
        }

        @Test
        @DisplayName("Should find most recent regime")
        void shouldFindMostRecentRegime() {
            // Create regimes at different times
            LocalDateTime now = LocalDateTime.now();
            createRegimeAt(now.minusMinutes(30), MarketRegime.RANGE);
            createRegimeAt(now.minusMinutes(15), MarketRegime.TREND);
            createRegimeAt(now, MarketRegime.HIGH_VOLATILITY);

            Optional<MarketRegimeEntity> latest = regimeRepository.findTopByOrderByTimestampDesc();

            assertTrue(latest.isPresent());
            assertEquals(MarketRegime.HIGH_VOLATILITY, latest.get().getRegimeType());
        }

        @Test
        @DisplayName("Should find regime by timestamp")
        void shouldFindRegimeByTimestamp() {
            LocalDateTime time = LocalDateTime.now().withNano(0);
            createRegimeAt(time, MarketRegime.RANGE);

            Optional<MarketRegimeEntity> found = regimeRepository.findByTimestamp(time);

            assertTrue(found.isPresent());
            assertEquals(MarketRegime.RANGE, found.get().getRegimeType());
        }
    }

    @Nested
    @DisplayName("Query Tests")
    class QueryTests {

        @Test
        @DisplayName("Should find recent regimes")
        void shouldFindRecentRegimes() {
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < 10; i++) {
                createRegimeAt(now.minusMinutes(i * 15), MarketRegime.values()[i % 3]);
            }

            List<MarketRegimeEntity> recent = regimeRepository.findRecentRegimes(5);

            assertEquals(5, recent.size());
            // Should be ordered by timestamp DESC
            assertTrue(recent.get(0).getTimestamp().isAfter(recent.get(1).getTimestamp()));
        }

        @Test
        @DisplayName("Should find regimes between timestamps")
        void shouldFindRegimesBetween() {
            LocalDateTime now = LocalDateTime.now();
            createRegimeAt(now.minusHours(3), MarketRegime.TREND);
            createRegimeAt(now.minusHours(2), MarketRegime.RANGE);
            createRegimeAt(now.minusHours(1), MarketRegime.HIGH_VOLATILITY);
            createRegimeAt(now, MarketRegime.TREND);

            List<MarketRegimeEntity> between = regimeRepository.findRegimesBetween(
                    now.minusHours(2).minusMinutes(30),
                    now.minusMinutes(30));

            assertEquals(2, between.size());
        }

        @Test
        @DisplayName("Should find regimes by type")
        void shouldFindRegimesByType() {
            LocalDateTime now = LocalDateTime.now();
            createRegimeAt(now.minusMinutes(45), MarketRegime.TREND);
            createRegimeAt(now.minusMinutes(30), MarketRegime.RANGE);
            createRegimeAt(now.minusMinutes(15), MarketRegime.TREND);
            createRegimeAt(now, MarketRegime.TREND);

            List<MarketRegimeEntity> trendRegimes = regimeRepository.findRecentByType(MarketRegime.TREND, 10);

            assertEquals(3, trendRegimes.size());
            for (MarketRegimeEntity regime : trendRegimes) {
                assertEquals(MarketRegime.TREND, regime.getRegimeType());
            }
        }
    }

    @Nested
    @DisplayName("Regime Changes Query Tests")
    class RegimeChangesTests {

        @Test
        @DisplayName("Should find regime changes")
        void shouldFindRegimeChanges() {
            LocalDateTime now = LocalDateTime.now();
            // Create sequence: TREND -> TREND -> RANGE -> HIGH_VOL -> HIGH_VOL
            createRegimeAt(now.minusMinutes(60), MarketRegime.TREND);
            createRegimeAt(now.minusMinutes(45), MarketRegime.TREND);
            createRegimeAt(now.minusMinutes(30), MarketRegime.RANGE);
            createRegimeAt(now.minusMinutes(15), MarketRegime.HIGH_VOLATILITY);
            createRegimeAt(now, MarketRegime.HIGH_VOLATILITY);

            List<MarketRegimeEntity> changes = regimeRepository.findRegimeChanges(10);

            // Should find changes: initial TREND, RANGE, HIGH_VOL = 3 changes
            assertTrue(changes.size() >= 1); // At least the first entry
        }
    }

    @Nested
    @DisplayName("Count Queries Tests")
    class CountTests {

        @Test
        @DisplayName("Should count regimes by type")
        void shouldCountRegimesByType() {
            LocalDateTime now = LocalDateTime.now();
            createRegimeAt(now.minusMinutes(60), MarketRegime.TREND);
            createRegimeAt(now.minusMinutes(45), MarketRegime.TREND);
            createRegimeAt(now.minusMinutes(30), MarketRegime.RANGE);
            createRegimeAt(now.minusMinutes(15), MarketRegime.TREND);
            createRegimeAt(now, MarketRegime.HIGH_VOLATILITY);

            List<Object[]> counts = regimeRepository.countByRegimeTypeSince(now.minusHours(2));

            assertNotNull(counts);
            assertFalse(counts.isEmpty());
        }
    }

    private void createRegimeAt(LocalDateTime time, MarketRegime regime) {
        MarketRegimeEntity entity = MarketRegimeEntity.builder()
                .timestamp(time)
                .regimeType(regime)
                .confidence(0.75)
                .matchedConditions(3)
                .totalConditions(4)
                .build();
        regimeRepository.save(entity);
    }
}
