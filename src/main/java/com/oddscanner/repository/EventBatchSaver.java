package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.parser.RawEvent;
import org.jooq.DSLContext;
import org.jooq.TableRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class EventBatchSaver {

    private static final Logger log = LoggerFactory.getLogger(EventBatchSaver.class);
    private final DSLContext dsl;

    public EventBatchSaver(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional(timeout = 120)
    public void saveBatch(Long bookmakerId, List<RawEvent> events) {
        if (events.isEmpty()) return;

        long start = System.currentTimeMillis();

        // --- ЭТАП 1: Массовая вставка СОБЫТИЙ (1 запрос) ---
        var eventInsert = dsl.insertInto(
                Tables.EVENTS,
                Tables.EVENTS.BOOKMAKER_ID,
                Tables.EVENTS.EXTERNAL_ID,
                Tables.EVENTS.LEAGUE,
                Tables.EVENTS.HOME_TEAM,
                Tables.EVENTS.AWAY_TEAM,
                Tables.EVENTS.START_TIME,
                Tables.EVENTS.STATUS,
                Tables.EVENTS.EVENT_URL
        );

        for (RawEvent e : events) {
            eventInsert = eventInsert.values(
                    bookmakerId, e.externalId(), e.leagueName(),
                    e.team1(), e.team2(), e.startsAt(),
                    "SCHEDULED", e.eventUrl()
            );
        }

        eventInsert
                .onConflict(Tables.EVENTS.BOOKMAKER_ID, Tables.EVENTS.EXTERNAL_ID)
                .doUpdate()
                .set(Tables.EVENTS.LEAGUE, org.jooq.impl.DSL.excluded(Tables.EVENTS.LEAGUE))
                .set(Tables.EVENTS.HOME_TEAM, org.jooq.impl.DSL.excluded(Tables.EVENTS.HOME_TEAM))
                .set(Tables.EVENTS.AWAY_TEAM, org.jooq.impl.DSL.excluded(Tables.EVENTS.AWAY_TEAM))
                .set(Tables.EVENTS.START_TIME, org.jooq.impl.DSL.excluded(Tables.EVENTS.START_TIME))
                .set(Tables.EVENTS.STATUS, "SCHEDULED")
                .set(Tables.EVENTS.EVENT_URL, org.jooq.impl.DSL.excluded(Tables.EVENTS.EVENT_URL))
                .set(Tables.EVENTS.UPDATED_AT, LocalDateTime.now())
                .execute();

        // --- ЭТАП 2: Получаем ID всех событий (1 запрос) ---
        List<String> extIds = events.stream().map(RawEvent::externalId).toList();
        Map<String, Long> eventIdMap = dsl.select(Tables.EVENTS.EXTERNAL_ID, Tables.EVENTS.ID)
                .from(Tables.EVENTS)
                .where(Tables.EVENTS.BOOKMAKER_ID.eq(bookmakerId))
                .and(Tables.EVENTS.EXTERNAL_ID.in(extIds))
                .fetchMap(Tables.EVENTS.EXTERNAL_ID, Tables.EVENTS.ID);

        // --- ЭТАП 3: Агрегация рынков и исходов в памяти (Java) ---
        record AggOutcome(String name, BigDecimal odds) {}
        record AggMarket(String type, Map<String, AggOutcome> outcomes) {}

        Map<Long, Map<String, AggMarket>> aggregated = new HashMap<>();

        for (RawEvent event : events) {
            Long eventId = eventIdMap.get(event.externalId());
            if (eventId == null) continue;

            for (RawEvent.RawMarket market : event.markets()) {
                aggregated.computeIfAbsent(eventId, k -> new HashMap<>())
                        .compute(market.marketType(), (k, existing) -> {
                            Map<String, AggOutcome> current = new HashMap<>();
                            if (existing != null) current.putAll(existing.outcomes());

                            for (RawEvent.RawOutcome out : market.outcomes()) {
                                current.merge(out.name(), new AggOutcome(out.name(), out.odds()),
                                        (oldV, newV) -> oldV.odds().compareTo(newV.odds()) >= 0 ? oldV : newV);
                            }
                            return new AggMarket(market.marketType(), current);
                        });
            }
        }

        // --- ЭТАП 4: Массовая вставка РЫНКОВ (1 запрос) ---
        List<Object[]> marketRows = new ArrayList<>();
        for (var entry : aggregated.entrySet()) {
            Long eid = entry.getKey();
            for (AggMarket m : entry.getValue().values()) {
                marketRows.add(new Object[]{eid, m.type(), m.type()});
            }
        }

        if (!marketRows.isEmpty()) {
            var mInsert = dsl.insertInto(Tables.MARKETS,
                    Tables.MARKETS.EVENT_ID, Tables.MARKETS.MARKET_TYPE, Tables.MARKETS.MARKET_NAME);

            for (Object[] row : marketRows) {
                mInsert = mInsert.values((Long) row[0], (String) row[1], (String) row[2]);
            }

            mInsert.onConflict(Tables.MARKETS.EVENT_ID, Tables.MARKETS.MARKET_TYPE)
                    .doUpdate()
                    .set(Tables.MARKETS.MARKET_NAME, org.jooq.impl.DSL.excluded(Tables.MARKETS.MARKET_NAME))
                    .execute();
        }

        // --- ЭТАП 5: Получаем ID всех рынков (1 запрос) ---
        Map<String, Long> marketIdMap = dsl.select(
                        Tables.MARKETS.EVENT_ID, Tables.MARKETS.MARKET_TYPE, Tables.MARKETS.ID)
                .from(Tables.MARKETS)
                .where(Tables.MARKETS.EVENT_ID.in(eventIdMap.values()))
                .fetchMap(r -> r.get(Tables.MARKETS.EVENT_ID) + "_" + r.get(Tables.MARKETS.MARKET_TYPE),
                        r -> r.get(Tables.MARKETS.ID));

        // --- ЭТАП 6: Удаляем старые исходы (1 запрос) ---
        if (!marketIdMap.isEmpty()) {
            dsl.deleteFrom(Tables.OUTCOMES)
                    .where(Tables.OUTCOMES.MARKET_ID.in(marketIdMap.values()))
                    .execute();
        }

        // --- ЭТАП 7: Массовая вставка ИСХОДОВ (1 batch запрос) ---
        // ИСПРАВЛЕНИЕ ТИПОВ: используем List<TableRecord<?>>
        List<TableRecord<?>> outcomeRecords = new ArrayList<>();

        for (var entry : aggregated.entrySet()) {
            Long eid = entry.getKey();
            for (AggMarket m : entry.getValue().values()) {
                String key = eid + "_" + m.type();
                Long mid = marketIdMap.get(key);
                if (mid == null) continue;

                for (AggOutcome out : m.outcomes().values()) {
                    var rec = dsl.newRecord(Tables.OUTCOMES);
                    rec.set(Tables.OUTCOMES.MARKET_ID, mid);
                    rec.set(Tables.OUTCOMES.OUTCOME_NAME, out.name());
                    rec.set(Tables.OUTCOMES.ODDS, out.odds());
                    rec.set(Tables.OUTCOMES.IS_ACTIVE, true);

                    outcomeRecords.add(rec);
                }
            }
        }

        if (!outcomeRecords.isEmpty()) {
            dsl.batchInsert(outcomeRecords).execute();
        }

        long end = System.currentTimeMillis();
        log.info("[SaveBatch] ✔ Сохранено {} событий, {} рынков, {} исходов за {} мс",
                events.size(), marketRows.size(), outcomeRecords.size(), (end - start));
    }
}