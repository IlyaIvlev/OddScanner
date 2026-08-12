package com.oddscanner.parser.winline;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WinlineParser extends AbstractBookmakerParser {

    private static final String BASE_URL = "https://winline.ru";

    // href и видимый текст ссылки на карточку события, снятые из DOM
    private record EventLink(String href, String text) {}

    public WinlineParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        super(meterRegistry, eventRepository);
    }

    @Override
    public String getName() {
        return "Winline";
    }

    @Override
    public List<RawEvent> doParse() throws Exception {
        List<RawEvent> allEvents = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of(
                            "--disable-blink-features=AutomationControlled",
                            "--no-sandbox",
                            "--disable-dev-shm-usage"
                    )));

            try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .setLocale("ru-RU")
                    .setTimezoneId("Europe/Moscow")
                    .setViewportSize(1920, 1080))) {

                Page page = context.newPage();

                page.addInitScript("""
                            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                            Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru', 'en'] });
                            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                            window.chrome = { runtime: {} };
                        """);

                page.navigate(BASE_URL, new Page.NavigateOptions()
                        .setTimeout(90_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                try {
                    page.waitForSelector("text=/\\d+\\.\\d{2}/", new Page.WaitForSelectorOptions()
                            .setTimeout(30_000));
                } catch (Exception e) {
                    log.warn("[Winline] Не удалось дождаться коэффициентов: {}", e.getMessage());
                }

                page.waitForTimeout(7000);

                // Снимаем реальные ссылки на события прямо из DOM
                List<EventLink> eventLinks = extractEventLinks(page);
                log.debug("[Winline] Найдено {} ссылок на события в DOM", eventLinks.size());

                String pageText = page.innerText("body");

                if (pageText != null && !pageText.isEmpty()) {
                    allEvents = parsePageText(pageText, eventLinks);
                } else {
                    log.warn("[Winline] Пустой текст страницы");
                }

                page.close();
            }

            browser.close();
        }

        List<RawEvent> deduped = allEvents.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(RawEvent::externalId, e -> e, (a, b) -> a),
                        map -> new ArrayList<>(map.values())
                ));

        if (!deduped.isEmpty()) {
            eventRepository.saveEvents("WINLINE", deduped);

            Set<String> activeExternalIds = deduped.stream()
                    .map(RawEvent::externalId)
                    .collect(Collectors.toSet());
            eventRepository.markInactiveEvents("WINLINE", activeExternalIds);

            log.debug("[Winline] Сохранено {} событий", deduped.size());
        } else {
            log.warn("[Winline] Не найдено событий");
        }

        return deduped;
    }

    /**
     * Достаёт все ссылки на карточки событий вида:
     * https://winline.ru/stavki/event/16453928 (Line)
     * https://winline.ru/live/sport/{sport}/{country}/{league}/{eventId} (Live)
     * Вместе с видимым текстом ссылки (там названия команд).
     */
    @SuppressWarnings("unchecked")
    private List<EventLink> extractEventLinks(Page page) {
        List<EventLink> result = new ArrayList<>();
        try {
            // Ищем любые ссылки, которые заканчиваются на /число (где число - ID события)
            List<Map<String, String>> raw = (List<Map<String, String>>) page.evaluate("""
                () => {
                    const eventIdRegex = /\\/(\\d+)$/;
                    return Array.from(document.querySelectorAll('a[href*="/stavki/event/"], a[href*="/live/sport/"]'))
                        .map(a => ({
                            href: a.href,
                            text: (a.innerText || '').replace(/\\s+/g, ' ').trim()
                        }))
                        .filter(x => x.href && x.text && x.text.length > 3 && eventIdRegex.test(x.href));
                }
                """);

            for (Map<String, String> m : raw) {
                result.add(new EventLink(m.get("href"), m.get("text")));
            }
        } catch (Exception e) {
            log.warn("[Winline] Ошибка извлечения ссылок событий: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Ищет среди собранных ссылок ту, чей текст содержит оба названия команд.
     * Если найдено несколько — берём с самым коротким текстом (более точечное совпадение).
     * Затем вытаскивает ID события из ссылки и формирует каноничную (всегда живую) ссылку.
     */
    private String findEventUrl(String team1, String team2, List<EventLink> eventLinks) {
        String t1 = team1.toLowerCase();
        String t2 = team2.toLowerCase();

        EventLink best = null;
        for (EventLink link : eventLinks) {
            String lower = link.text().toLowerCase();
            if (lower.contains(t1) && lower.contains(t2)) {
                if (best == null || link.text().length() < best.text().length()) {
                    best = link;
                }
            }
        }

        if (best != null) {
            // Вытаскиваем ID события из ссылки (последнее число в URL)
            String eventId = extractEventId(best.href());
            if (eventId != null && !eventId.isEmpty()) {
                // Возвращаем каноничную ссылку на линию, которая редиректит в Live при необходимости
                return BASE_URL + "/stavki/event/" + eventId;
            }
        }

        // Fallback, если карточку не нашли (редкий случай) — ведём хотя бы на линию сайта
        return BASE_URL + "/line";
    }

    /**
     * Извлекает цифровой ID события из любого формата ссылки Winline.
     */
    private String extractEventId(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            Matcher m = Pattern.compile("/(\\d+)$").matcher(url);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            log.warn("[Winline] Ошибка извлечения ID из URL: {}", e.getMessage());
        }
        return null;
    }

    private List<RawEvent> parsePageText(String text, List<EventLink> eventLinks) {
        List<RawEvent> events = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        String[] lines = text.split("\\n");

        for (int i = 0; i < lines.length - 4; i++) {
            String line1 = lines[i].trim();
            String line2 = lines[i + 1].trim();

            if (line1.length() < 3 || line2.length() < 3) continue;
            if (line1.matches("\\d+") || line2.matches("\\d+")) continue;
            if (line1.matches(".*\\d+\\.\\d{2}.*")) continue;
            if (line2.matches(".*\\d+\\.\\d{2}.*")) continue;
            if (!line1.matches(".*[А-Яа-яA-Za-z]{3,}.*")) continue;
            if (!line2.matches(".*[А-Яа-яA-Za-z]{3,}.*")) continue;
            if (isServiceLine(line1) || isServiceLine(line2)) continue;

            List<BigDecimal> odds = new ArrayList<>();
            int lastOddIdx = i + 1;

            for (int j = i + 2; j < Math.min(i + 12, lines.length); j++) {
                String oddLine = lines[j].trim();

                if (oddLine.matches("[1XxХх2]|П[12]|Да|Нет|Over|Under")) continue;
                if (isServiceLine(oddLine)) continue;

                Matcher m = Pattern.compile("^(\\d+\\.\\d{2})$").matcher(oddLine);
                if (m.matches()) {
                    BigDecimal odd = new BigDecimal(m.group(1));
                    if (odd.compareTo(BigDecimal.ONE) > 0 && odd.compareTo(BigDecimal.valueOf(50)) < 0) {
                        odds.add(odd);
                        lastOddIdx = j;
                    }
                }

                if (odds.size() == 3) break;

                if (oddLine.matches(".*[А-Яа-яA-Za-z]{4,}.*") && !oddLine.matches(".*\\d+\\.\\d{2}.*") && odds.size() >= 2) {
                    break;
                }
            }

            if (odds.size() < 2) continue;

            String team1 = cleanTeamName(line1);
            String team2 = cleanTeamName(line2);

            if (team1.length() < 2 || team2.length() < 2) continue;

            String key = team1.toLowerCase() + "|" + team2.toLowerCase();
            if (processed.contains(key)) continue;
            processed.add(key);

            List<RawEvent.RawOutcome> outcomes = new ArrayList<>();
            if (odds.size() >= 3) {
                outcomes.add(new RawEvent.RawOutcome("П1", odds.get(0)));
                outcomes.add(new RawEvent.RawOutcome("Х", odds.get(1)));
                outcomes.add(new RawEvent.RawOutcome("П2", odds.get(2)));
            } else {
                outcomes.add(new RawEvent.RawOutcome("П1", odds.get(0)));
                outcomes.add(new RawEvent.RawOutcome("П2", odds.get(1)));
            }

            List<RawEvent.RawMarket> markets = List.of(
                    new RawEvent.RawMarket("1X2", outcomes));

            String dateKey = LocalDateTime.now().toLocalDate().toString();
            String eventId = "w_" + team1.toLowerCase().replaceAll("\\s+", "_")
                    + "_" + team2.toLowerCase().replaceAll("\\s+", "_")
                    + "_" + dateKey;

            // Настоящая ссылка на карточку события, найденная в DOM и преобразованная в каноничный вид
            String eventUrl = findEventUrl(team1, team2, eventLinks);

            events.add(new RawEvent(
                    eventId, "Sport", "Winline",
                    team1, team2,
                    LocalDateTime.now().plusHours(2),
                    markets,
                    eventUrl
            ));

            log.debug("[Winline] Найден матч: {} vs {} | {} | url={}", team1, team2, odds, eventUrl);

            i = lastOddIdx;
        }

        log.debug("[Winline] Распарсено {} событий из текста", events.size());
        return events;
    }

    private boolean isServiceLine(String line) {
        Set<String> serviceWords = Set.of(
                "линия", "live", "сейчас", "игры", "киберспорт", "войти", "регистрация",
                "избранное", "ближайшие", "футбол", "теннис", "баскетбол", "хоккей",
                "волейбол", "мма", "бокс", "главные", "события", "матч", "дня",
                "популярные", "результаты", "о нас", "мой счет", "winline", "support",
                "онлайн", "чат", "работа", "игрокам", "программа", "лояльности",
                "служба", "поддержки", "клубы", "блог", "документы", "основные",
                "перейти", "вернуться", "главную", "страница", "не найдена",
                "исход", "тотал", "фора", "обе", "забьют"
        );
        String lower = line.toLowerCase().trim();
        return serviceWords.stream().anyMatch(lower::contains) && lower.length() < 30;
    }

    private String cleanTeamName(String name) {
        return name
                .replaceAll("\\bСегодня\\b", "")
                .replaceAll("\\bЗавтра\\b", "")
                .replaceAll("\\b\\d{2}:\\d{2}\\b", "")
                .replaceAll("\\b\\d+\\b", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}