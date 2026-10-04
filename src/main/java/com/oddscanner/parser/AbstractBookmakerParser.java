package com.oddscanner.parser;

import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public abstract class AbstractBookmakerParser implements BookmakerParser {

    private final MeterRegistry meterRegistry;
    protected final EventRepository eventRepository;
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected AbstractBookmakerParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        this.meterRegistry = meterRegistry;
        this.eventRepository = eventRepository;
    }

    /**
     * Вызывается из ScanOrchestrator — парсит и сохраняет события.
     */
    public List<RawEvent> parseAndSave() {
        String name = getName();

        if (!eventRepository.isBookmakerActive(name)) {
            return List.of();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";

        try {
            var events = doParse();

            meterRegistry.counter("parser.events.total", "bookmaker", name)
                    .increment(events.size());

            return events;

        } catch (Exception e) {
            status = "error";
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("parser.duration.seconds")
                    .tag("bookmaker", name)
                    .tag("status", status)
                    .register(meterRegistry));

            meterRegistry.counter("parser.runs.total", "bookmaker", name, "status", status)
                    .increment();
        }
    }
}