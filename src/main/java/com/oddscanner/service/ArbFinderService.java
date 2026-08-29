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
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArbFinderService {

    private final DSLContext dsl;

    public List<ArbitrageOpportunityDto> findArbitrages() {
        log.info("🔍 Запуск поиска вилок...");

        // Оптимизация: выбираем только нужные поля, если их много
        Result<EventsRecord> events = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.STATUS.eq("SCHEDULED"))
                .fetch();

        Map<String, List<EventsRecord>> groupedEvents = events.stream()
                .collect(Collectors.groupingBy(e ->
                        normalizeMatchKey(e.getHomeTeam(), e.getAwayTeam())
                ));

        List<ArbitrageOpportunityDto> opportunities = new ArrayList<>();

        for (List<EventsRecord> group : groupedEvents.values()) {
            if (group.size() < 2) continue;

            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    EventsRecord e1 = group.get(i);
                    EventsRecord e2 = group.get(j);

                    // Пропускаем пары из одной БК
                    if (Objects.equals(e1.getBookmakerId(), e2.getBookmakerId())) continue;

                    checkMarketsUniversal(e1, e2, opportunities);
                }
            }
        }

        log.info("✅ Поиск завершен. Найдено {} вилок.", opportunities.size());
        return opportunities;
    }

    private void checkMarketsUniversal(EventsRecord e1, EventsRecord e2,
                                       List<ArbitrageOpportunityDto> list) {
        Map<String, MarketData> m1 = getEventMarketData(e1.getId());
        Map<String, MarketData> m2 = getEventMarketData(e2.getId());

        for (String marketType : m1.keySet()) {
            if (!m2.containsKey(marketType)) continue;

            MarketData data1 = m1.get(marketType);
            MarketData data2 = m2.get(marketType);

            if ("1X2".equals(marketType) && data1.hasAllOutcomes() && data2.hasAllOutcomes()) {

                // Находим лучшие кэфы и определяем, из какого события они взяты
                BestOddResult p1Result = getBestOddWithSource(data1.getP1(), data2.getP1(), e1, e2);
                BestOddResult xResult = getBestOddWithSource(data1.getX(), data2.getX(), e1, e2);
                BestOddResult p2Result = getBestOddWithSource(data1.getP2(), data2.getP2(), e1, e2);

                if (p1Result.odds() == null || xResult.odds() == null || p2Result.odds() == null) continue;

                BigDecimal bestP1 = p1Result.odds();
                BigDecimal bestX = xResult.odds();
                BigDecimal bestP2 = p2Result.odds();

                double margin = 1.0 / bestP1.doubleValue() +
                        1.0 / bestX.doubleValue() +
                        1.0 / bestP2.doubleValue();

                if (margin < 1.0) {
                    ArbitrageOpportunityDto opportunity = buildOpportunity(marketType, p1Result, xResult, p2Result);
                    if (opportunity != null) {
                        list.add(opportunity);
                    }
                }
            }
        }
    }

    private ArbitrageOpportunityDto buildOpportunity(String marketType,
                                                     BestOddResult p1Res,
                                                     BestOddResult xRes,
                                                     BestOddResult p2Res) {
        double bank = 100.0;
        BigDecimal p1 = p1Res.odds();
        BigDecimal x = xRes.odds();
        BigDecimal p2 = p2Res.odds();

        // Защита от деления на ноль или null (хотя выше есть проверки, но для надежности)
        if (p1 == null || x == null || p2 == null) {
            return null;
        }

        double invSum = 1.0 / p1.doubleValue() + 1.0 / x.doubleValue() + 1.0 / p2.doubleValue();

        double stake1 = (bank / p1.doubleValue()) / invSum;
        double stakeX = (bank / x.doubleValue()) / invSum;
        double stake2 = (bank / p2.doubleValue()) / invSum;

        // Формируем название матча из источника первого исхода (они должны совпадать по названию)
        String matchName = p1Res.source().getHomeTeam() + " vs " + p1Res.source().getAwayTeam();

        return new ArbitrageOpportunityDto(
                matchName,
                marketType,
                Math.round(invSum * 10000.0) / 10000.0,

                // Доходность в процентах
                Math.round(((1.0 / invSum) - 1) * 10000.0) / 100.0,

                createOutcomeInfo("1", p1, p1Res.source(), stake1),
                createOutcomeInfo("X", x, xRes.source(), stakeX),
                createOutcomeInfo("2", p2, p2Res.source(), stake2)
        );
    }

    /**
     * Создает информацию об исходе без дополнительных запросов в БД.
     */
    private OutcomeInfo createOutcomeInfo(String outcomeName, BigDecimal odds,
                                          EventsRecord sourceEvent, double stake) {
        return new OutcomeInfo(
                outcomeName,
                odds,
                getBookmakerName(sourceEvent.getBookmakerId()), // Можно закэшировать мапу ID->Name
                sourceEvent.getEventUrl(),
                Math.round(stake * 100.0) / 100.0
        );
    }

    /**
     * Вспомогательный класс для хранения лучшего коэффициента и источника (события)
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
                .select(Tables.MARKETS.MARKET_TYPE, Tables.OUTCOMES.OUTCOME_NAME, Tables.OUTCOMES.ODDS)
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

            if ("1X2".equals(type)) {
                result.computeIfAbsent(type, k -> new MarketData())
                        .addOutcome(outcome, odds);
            }
        }
        return result;
    }

    private String getBookmakerName(Long bookmakerId) {
        // Внимание: этот метод все еще делает запрос.
        // Для высокой производительности лучше загрузить все Bookmakers в Map<Long, String> при старте или в начале метода findArbitrages
        return dsl.select(Tables.BOOKMAKERS.NAME)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.ID.eq(bookmakerId))
                .fetchOneInto(String.class);
    }

    private String normalizeMatchKey(String home, String away) {
        if (home == null || away == null) return "";
        String h = home.toLowerCase().replaceAll("[^a-zа-яё0-9]", "").trim();
        String a = away.toLowerCase().replaceAll("[^a-zа-яё0-9]", "").trim();
        return h.compareTo(a) <= 0 ? h + "|" + a : a + "|" + h;
    }

    private static class MarketData {
        private BigDecimal p1, x, p2;

        void addOutcome(String outcome, BigDecimal odds) {
            switch (outcome.toUpperCase()) {
                case "1", "П1", "HOME" -> p1 = odds;
                case "X", "Х", "DRAW", "НИЧЬЯ" -> x = odds;
                case "2", "П2", "AWAY" -> p2 = odds;
            }
        }

        boolean hasAllOutcomes() {
            return p1 != null && x != null && p2 != null;
        }

        BigDecimal getP1() { return p1; }
        BigDecimal getX() { return x; }
        BigDecimal getP2() { return p2; }
    }
}