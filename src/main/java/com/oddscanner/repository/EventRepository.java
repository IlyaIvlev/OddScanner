package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.parser.RawEvent;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Repository
public class EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private final DSLContext dsl;
    private final EventBatchSaver batchSaver; // Инжектим другой бин

    // УМЕНЬШАЕМ ДО 50. Это критично для скорости.
    private static final int BATCH_SIZE = 3000;

    public EventRepository(DSLContext dsl, EventBatchSaver batchSaver) {
        this.dsl = dsl;
        this.batchSaver = batchSaver;
    }

    public void saveEvents(String bookmakerCode, List<RawEvent> events) {
        if (events.isEmpty()) return;

        Long bookmakerId = getBookmakerId(bookmakerCode);
        if (bookmakerId == null) {
            log.error("[EventRepository] Букмекер с кодом {} не найден!", bookmakerCode);
            return;
        }

        log.debug("[EventRepository] Начало сохранения {} событий для {}", events.size(), bookmakerCode);

        for (int i = 0; i < events.size(); i += BATCH_SIZE) {
            List<RawEvent> batch = events.subList(i, Math.min(i + BATCH_SIZE, events.size()));
            try {
                batchSaver.saveBatch(bookmakerId, batch);
            } catch (Exception e) {
                log.error("[EventRepository] Ошибка сохранения пачки ({}-{}): {}",
                        i, i + batch.size(), e.getMessage());
            }
        }
        log.debug("[EventRepository] Завершено сохранение для {}", bookmakerCode);
    }

    private Long getBookmakerId(String code) {
        return dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(org.jooq.impl.DSL.upper(Tables.BOOKMAKERS.CODE).eq(code.toUpperCase()))
                .fetchOne(Tables.BOOKMAKERS.ID);
    }

    public void markInactiveEvents(String bookmakerCode, Set<String> activeExternalIds) {
        if (activeExternalIds.isEmpty()) return;
        Long bookmakerId = getBookmakerId(bookmakerCode);
        if (bookmakerId == null) return;

        dsl.update(Tables.EVENTS)
                .set(Tables.EVENTS.STATUS, "INACTIVE")
                .set(Tables.EVENTS.UPDATED_AT, LocalDateTime.now())
                .where(Tables.EVENTS.BOOKMAKER_ID.eq(bookmakerId))
                .and(Tables.EVENTS.EXTERNAL_ID.notIn(activeExternalIds))
                .and(Tables.EVENTS.STATUS.ne("INACTIVE"))
                .execute();
    }

    public boolean isBookmakerActive(String code) {
        Boolean isActive = dsl.select(Tables.BOOKMAKERS.IS_ACTIVE)
                .from(Tables.BOOKMAKERS)
                .where(org.jooq.impl.DSL.upper(Tables.BOOKMAKERS.CODE).eq(code.toUpperCase()))
                .fetchOne(Tables.BOOKMAKERS.IS_ACTIVE);
        return isActive != null && isActive;
    }
}