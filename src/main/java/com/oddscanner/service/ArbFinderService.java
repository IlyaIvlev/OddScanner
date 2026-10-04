package com.oddscanner.service;

import com.oddscanner.dto.ArbitrageOpportunityDto;
import com.oddscanner.dto.ArbitrageOpportunityDto.OutcomeInfo;
import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Result;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArbFinderService {

    /** Окно времени между матчами у разных БК (секунды). */
    private static final long TIME_WINDOW_SECONDS = 900; // ±15 минут

    /** Минимальная прибыль, чтобы считать вилку интересной. 0 = все вилки. */
    private static final double MIN_PROFIT_PERCENT = 0.0;

    private final DSLContext dsl;

    public List<ArbitrageOpportunityDto> findArbitrages() {
        log.info("🔍 Запуск поиска вилок...");

        Map<Long, String> bookmakerNames = loadBookmakerNames();

        Result<EventsRecord> events = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.STATUS.eq("SCHEDULED"))
                .and(Tables.EVENTS.START_TIME.gt(LocalDateTime.now()))
                .fetch();

        log.info("📋 Загружено {} событий для поиска вилок", events.size());

        // Группировка по нормализованному названию матча + КАЛЕНДАРНАЯ ДАТА
        // Это отсекает матчи разных туров одной пары команд (Ливерпуль-Сити 30.09 vs 11.10)
        Map<String, List<EventsRecord>> groupedEvents = events.stream()
                .collect(Collectors.groupingBy(e ->
                        normalizeMatchKey(e.getHomeTeam(), e.getAwayTeam())
                                + "|" + e.getStartTime().toLocalDate()
                ));

        List<ArbitrageOpportunityDto> opportunities = new ArrayList<>();
        int pairsChecked = 0;
        int pairsSkippedByTime = 0;
        int pairsSkippedByBookmaker = 0;

        for (List<EventsRecord> group : groupedEvents.values()) {
            if (group.size() < 2) continue;

            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    EventsRecord e1 = group.get(i);
                    EventsRecord e2 = group.get(j);

                    if (Objects.equals(e1.getBookmakerId(), e2.getBookmakerId())) {
                        pairsSkippedByBookmaker++;
                        continue;
                    }

                    // Окно по времени внутри одного дня — можно сузить до 15 мин
                    long diffSec = Math.abs(Duration.between(
                            e1.getStartTime(), e2.getStartTime()).getSeconds());
                    if (diffSec > TIME_WINDOW_SECONDS) {
                        pairsSkippedByTime++;
                        continue;
                    }

                    pairsChecked++;
                    checkMarketsUniversal(e1, e2, bookmakerNames, opportunities);
                }
            }
        }

        log.info("✅ Поиск завершен. Проверено пар: {}, найдено вилок: {} " +
                        "(пропущено по времени: {}, по букмекеру: {})",
                pairsChecked, opportunities.size(), pairsSkippedByTime, pairsSkippedByBookmaker);
        return opportunities;
    }

    /**
     * Загружает все имена букмекеров одним запросом.
     */
    private Map<Long, String> loadBookmakerNames() {
        return dsl.select(Tables.BOOKMAKERS.ID, Tables.BOOKMAKERS.NAME)
                .from(Tables.BOOKMAKERS)
                .fetchMap(Tables.BOOKMAKERS.ID, Tables.BOOKMAKERS.NAME);
    }

    private void checkMarketsUniversal(EventsRecord e1,
                                       EventsRecord e2,
                                       Map<Long, String> bookmakerNames,
                                       List<ArbitrageOpportunityDto> list) {
        Map<String, MarketData> m1 = getEventMarketData(e1.getId());
        Map<String, MarketData> m2 = getEventMarketData(e2.getId());

        for (String marketType : m1.keySet()) {
            if (!m2.containsKey(marketType)) continue;

            MarketData data1 = m1.get(marketType);
            MarketData data2 = m2.get(marketType);

            if ("1X2".equals(marketType) && data1.hasAllOutcomes() && data2.hasAllOutcomes()) {

                BestOddResult p1Result = getBestOddWithSource(data1.getP1(), data2.getP1(), e1, e2);
                BestOddResult xResult = getBestOddWithSource(data1.getX(), data2.getX(), e1, e2);
                BestOddResult p2Result = getBestOddWithSource(data1.getP2(), data2.getP2(), e1, e2);

                if (p1Result.odds() == null || xResult.odds() == null || p2Result.odds() == null) continue;

                BigDecimal bestP1 = p1Result.odds();
                BigDecimal bestX = xResult.odds();
                BigDecimal bestP2 = p2Result.odds();

                double margin = 1.0 / bestP1.doubleValue()
                        + 1.0 / bestX.doubleValue()
                        + 1.0 / bestP2.doubleValue();

                if (margin < 1.0) {
                    double profitPercent = (1.0 / margin - 1) * 100.0;
                    if (profitPercent >= MIN_PROFIT_PERCENT) {
                        ArbitrageOpportunityDto opportunity = buildOpportunity(
                                marketType, p1Result, xResult, p2Result, bookmakerNames);
                        if (opportunity != null) {
                            list.add(opportunity);
                        }
                    }
                }
            }
        }
    }

    private ArbitrageOpportunityDto buildOpportunity(String marketType,
                                                     BestOddResult p1Res,
                                                     BestOddResult xRes,
                                                     BestOddResult p2Res,
                                                     Map<Long, String> bookmakerNames) {
        double bank = 100.0;
        BigDecimal p1 = p1Res.odds();
        BigDecimal x = xRes.odds();
        BigDecimal p2 = p2Res.odds();

        if (p1 == null || x == null || p2 == null) {
            return null;
        }

        double invSum = 1.0 / p1.doubleValue()
                + 1.0 / x.doubleValue()
                + 1.0 / p2.doubleValue();

        double stake1 = (bank / p1.doubleValue()) / invSum;
        double stakeX = (bank / x.doubleValue()) / invSum;
        double stake2 = (bank / p2.doubleValue()) / invSum;

        String matchName = p1Res.source().getHomeTeam() + " vs " + p1Res.source().getAwayTeam();

        return new ArbitrageOpportunityDto(
                matchName,
                marketType,
                Math.round(invSum * 10000.0) / 10000.0,
                Math.round(((1.0 / invSum) - 1) * 10000.0) / 100.0,
                createOutcomeInfo("1", p1, p1Res.source(), stake1, bookmakerNames),
                createOutcomeInfo("X", x, xRes.source(), stakeX, bookmakerNames),
                createOutcomeInfo("2", p2, p2Res.source(), stake2, bookmakerNames)
        );
    }

    private OutcomeInfo createOutcomeInfo(String outcomeName,
                                          BigDecimal odds,
                                          EventsRecord sourceEvent,
                                          double stake,
                                          Map<Long, String> bookmakerNames) {
        String bookmakerName = bookmakerNames.getOrDefault(
                sourceEvent.getBookmakerId(), "Unknown");
        return new OutcomeInfo(
                outcomeName,
                odds,
                bookmakerName,
                sourceEvent.getEventUrl(),
                Math.round(stake * 100.0) / 100.0
        );
    }

    /**
     * Вспомогательный класс для хранения лучшего коэффициента и источника.
     */
    private record BestOddResult(BigDecimal odds, EventsRecord source) {}

    private BestOddResult getBestOddWithSource(BigDecimal val1, BigDecimal val2,
                                               EventsRecord e1, EventsRecord e2) {
        if (val1 == null && val2 == null) return new BestOddResult(null, null);
        if (val1 == null) return new BestOddResult(val2, e2);
        if (val2 == null) return new BestOddResult(val1, e1);

        return val1.compareTo(val2) >= 0
                ? new BestOddResult(val1, e1)
                : new BestOddResult(val2, e2);
    }

    private Map<String, MarketData> getEventMarketData(Long eventId) {
        Result<Record3<String, String, BigDecimal>> records = dsl
                .select(Tables.MARKETS.MARKET_TYPE,
                        Tables.OUTCOMES.OUTCOME_NAME,
                        Tables.OUTCOMES.ODDS)
                .from(Tables.MARKETS)
                .join(Tables.OUTCOMES).on(Tables.MARKETS.ID.eq(Tables.OUTCOMES.MARKET_ID))
                .where(Tables.MARKETS.EVENT_ID.eq(eventId))
                .and(Tables.OUTCOMES.IS_ACTIVE.eq(true))
                .fetch();

        Map<String, MarketData> result = new HashMap<>();
        for (var r : records) {
            String type = r.get(Tables.MARKETS.MARKET_TYPE);
            String outcome = r.get(Tables.OUTCOMES.OUTCOME_NAME);
            BigDecimal odds = r.get(Tables.OUTCOMES.ODDS);

            if (!"1X2".equals(type) || odds == null) continue;
            if (odds.doubleValue() < 1.01 || odds.doubleValue() > 1000) continue;

            String upper = outcome.toUpperCase().trim();
            if (!isKnown1X2Outcome(upper)) continue;

            result.computeIfAbsent(type, k -> new MarketData())
                    .addOutcome(upper, odds);
        }
        return result;
    }

    /**
     * Белый список исходов 1X2. Отсекает мусор типа "To_Win_Match_With_Handicap.HB_H",
     * "X2", "1X", "12" (двойной шанс), "Победа 1" и т.п.
     * Включает "3" — так Marathon обозначает П2.
     */
    private boolean isKnown1X2Outcome(String upper) {
        return switch (upper) {
            case "1", "П1", "HOME", "W1",
                 "X", "Х", "DRAW", "НИЧЬЯ", "TIE",
                 "2", "П2", "AWAY", "W2",
                 "3"                                  // Marathon: "3" = П2
                    -> true;
            default -> false;
        };
    }

    /**
     * Нормализация ключа матча: убираем регистр, пунктуацию, сортируем команды.
     */
    private String normalizeMatchKey(String home, String away) {
        if (home == null || away == null) return "";
        String h = home.toLowerCase().replaceAll("[^a-zа-яё0-9]", "").trim();
        String a = away.toLowerCase().replaceAll("[^a-zа-яё0-9]", "").trim();
        return h.compareTo(a) <= 0 ? h + "|" + a : a + "|" + h;
    }

    /**
     * Внутренний класс для хранения исходов 1X2 одного события.
     * Один экземпляр на (event, market_type).
     */
    private static class MarketData {
        private BigDecimal p1;
        private BigDecimal x;
        private BigDecimal p2;

        void addOutcome(String outcome, BigDecimal odds) {
            switch (outcome) {
                case "1", "П1", "HOME", "W1" -> p1 = odds;
                case "X", "Х", "DRAW", "НИЧЬЯ", "TIE" -> x = odds;
                case "2", "П2", "AWAY", "W2", "3" -> p2 = odds;  // "3" — Marathon
            }
        }

        boolean hasAllOutcomes() {
            return p1 != null && x != null && p2 != null;
        }

        BigDecimal getP1() { return p1; }
        BigDecimal getX()  { return x; }
        BigDecimal getP2() { return p2; }
    }
}