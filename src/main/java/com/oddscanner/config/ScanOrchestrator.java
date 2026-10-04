package com.oddscanner.config;

import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.BookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScanOrchestrator implements CommandLineRunner {

    private final List<BookmakerParser> parsers;
    private final EventRepository eventRepository;

    @Value("${parser.clear-events-on-startup:false}")
    private boolean clearEventsOnStartup;

    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_ERROR_LEN = 22;

    // Этот метод вызовется ОДИН РАЗ после полного запуска приложения
    @Override
    public void run(String... args) throws Exception {
        if (clearEventsOnStartup) {
            log.warn("🧹 Активирована опция очистки событий при запуске.");
            eventRepository.clearAllEvents();
        }

        // Выводим статус парсеров ОДИН РАЗ при старте
        printReport("🤖 ПАРСЕРЫ ПРИ ЗАПУСКЕ", null, null);
    }

    @Scheduled(fixedDelayString = "${parser.scan-interval:45000}")
    public void runScan() {
        String timestamp = LocalDateTime.now().format(FMT);

        Map<String, Boolean> activeMap = new LinkedHashMap<>();
        for (BookmakerParser parser : parsers) {
            activeMap.put(parser.getName(), eventRepository.isBookmakerActive(parser.getName()));
        }

        List<BookmakerParser> activeParsers = parsers.stream()
                .filter(p -> activeMap.getOrDefault(p.getName(), false))
                .toList();

        CountDownLatch latch = new CountDownLatch(activeParsers.size());
        ConcurrentHashMap<String, ParseResult> results = new ConcurrentHashMap<>();

        for (BookmakerParser parser : activeParsers) {
            executor.submit(() -> {
                String name = parser.getName();
                try {
                    if (parser instanceof AbstractBookmakerParser abp) {
                        List<RawEvent> events = abp.parseAndSave();
                        results.put(name, new ParseResult(events.size(), null));
                    } else {
                        List<RawEvent> events = parser.doParse();
                        results.put(name, new ParseResult(events != null ? events.size() : 0, null));
                    }
                } catch (Exception e) {
                    results.put(name, new ParseResult(0, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(1800, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[ScanOrchestrator] Не все парсеры успели завершиться за таймаут");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        printReport("📊 СКАНИРОВАНИЕ " + timestamp, results, activeMap);
    }

    private void printReport(String title, ConcurrentHashMap<String, ParseResult> results, Map<String, Boolean> activeMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");

        int width = 58;
        String h = "═".repeat(width);
        String border = "╔" + h + "╗";
        String separator = "╠" + h + "╣";
        String bottom = "╚" + h + "╝";

        sb.append(border).append("\n");

        int pad = (width - title.length()) / 2;
        sb.append("║").append(" ".repeat(Math.max(0, pad)))
                .append(title)
                .append(" ".repeat(Math.max(0, width - pad - title.length())))
                .append("║\n");

        sb.append(separator).append("\n");

        int totalEvents = 0;
        int activeCount = 0;

        for (BookmakerParser parser : parsers) {
            String name = parser.getName();
            boolean isActive = (activeMap != null)
                    ? activeMap.getOrDefault(name, false)
                    : eventRepository.isBookmakerActive(name);

            String icon;
            String statusStr;

            if (!isActive) {
                icon = "⏸️";
                statusStr = "неактивен";
            } else {
                activeCount++;
                if (results == null) {
                    icon = "✅";
                    statusStr = "активен";
                } else {
                    ParseResult result = results.getOrDefault(name, new ParseResult(0, "таймаут"));
                    if (result.error() != null) {
                        icon = "❌";
                        statusStr = "ОШИБКА: " + truncate(result.error());
                    } else {
                        icon = "✅";
                        statusStr = result.count() + " событий";
                        totalEvents += result.count();
                    }
                }
            }

            String namePart = String.format("%-11s", name);
            int statusWidth = 38;
            String statusPart = String.format("%-" + statusWidth + "s", statusStr);

            String line = "║ " + icon + " " + namePart + " │ " + statusPart + " ║";
            sb.append(line).append("\n");
        }

        sb.append(separator).append("\n");

        if (results != null) {
            String footer = String.format("Итого: %d событий │ %d активных / %d всего",
                    totalEvents, activeCount, parsers.size());
            sb.append(String.format("║ %-56s ║", footer)).append("\n");
        }

        sb.append(bottom);
        log.info(sb.toString());
    }

    private String truncate(String s) {
        if (s == null) return "null";
        return s.length() > MAX_ERROR_LEN ? s.substring(0, MAX_ERROR_LEN) + "..." : s;
    }

    private record ParseResult(int count, String error) {}
}