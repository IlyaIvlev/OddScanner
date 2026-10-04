package com.oddscanner.parser.bet365;

import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class Bet365Parser extends AbstractBookmakerParser {
    private final EventRepository eventRepository;

    public Bet365Parser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        super(meterRegistry, eventRepository);
        this.eventRepository = eventRepository;
    }

    @Override
    public String getName() {
        return "Bet365";
    }

    @Override
    public List<RawEvent> doParse() throws Exception {
        List<RawEvent> events = new ArrayList<>();

        // TODO: Реальная логика парсинга Bet365
        // Для MVP создадим тестовые данные

//        RawEvent testEvent = new RawEvent(
//                "BET365_TEST_" + System.currentTimeMillis(),
//                "Теннис",
//                "ATP",
//                "Надаль",
//                "Джокович",
//                LocalDateTime.now().plusHours(1),
//                List.of(
//                        new RawEvent.RawMarket("Winner", List.of(
//                                new RawEvent.RawOutcome("Надаль", new BigDecimal("2.50")),
//                                new RawEvent.RawOutcome("Джокович", new BigDecimal("1.55"))
//                        ))
//                ),
//                ""
//        );
//
//        events.add(testEvent);
//        eventRepository.saveEvents("BET365", events);
//
//        log.info("[Bet365] Сохранено {} событий", events.size());
        return events;
    }
}