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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WinlineParser extends AbstractBookmakerParser {

    private static final String BASE_URL = "https://winline.ru";

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

                // Stealth: убираем webdriver-флаг
                page.addInitScript("""
                            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                            Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru', 'en'] });
                            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                            window.chrome = { runtime: {} };
                        """);

                //log.debug("[Winline] Открываю главную страницу...");
                page.navigate(BASE_URL, new Page.NavigateOptions()
                        .setTimeout(90_000)  // было 60_000
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));  // было NETWORKIDLE

                // Ждём появления коэффициентов — увеличим таймаут
                try {
                    page.waitForSelector("text=/\\d+\\.\\d{2}/", new Page.WaitForSelectorOptions()
                            .setTimeout(30_000));  // было 15_000
                } catch (Exception e) {
                    log.warn("[Winline] Не удалось дождаться коэффициентов: {}", e.getMessage());
                }

                page.waitForTimeout(7000);  // было 5000 — дадим странице дорендериться

                // Извлекаем текст страницы
                String pageText = page.innerText("body");

                if (pageText != null && !pageText.isEmpty()) {
                    //log.debug("[Winline] Длина текста: {} символов", pageText.length());
                    allEvents = parsePageText(pageText);
                } else {
                    log.warn("[Winline] Пустой текст страницы");
                }

                page.close();
            }

            browser.close();
        }

        // Дедупликация
        List<RawEvent> deduped = allEvents.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(RawEvent::externalId, e -> e, (a, b) -> a),
                        map -> new ArrayList<>(map.values())
                ));

        //log.info("[Winline] Итого: {} событий распарсено", deduped.size());

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

    private List<RawEvent> parsePageText(String text) {
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

            // Собираем ВСЕ коэффициенты из следующих строк (П1, Х, П2)
            List<BigDecimal> odds = new ArrayList<>();
            int lastOddIdx = i + 1;

            for (int j = i + 2; j < Math.min(i + 12, lines.length); j++) {
                String oddLine = lines[j].trim();

                // Пропускаем метки исходов: "1", "X", "2", "П1", "Х", "П2"
                if (oddLine.matches("[1XxХх2]|П[12]|Да|Нет|Over|Under")) continue;

                // Пропускаем служебные слова
                if (isServiceLine(oddLine)) continue;

                // Ищем коэффициент
                Matcher m = Pattern.compile("^(\\d+\\.\\d{2})$").matcher(oddLine);
                if (m.matches()) {
                    BigDecimal odd = new BigDecimal(m.group(1));
                    if (odd.compareTo(BigDecimal.ONE) > 0 && odd.compareTo(BigDecimal.valueOf(50)) < 0) {
                        odds.add(odd);
                        lastOddIdx = j;
                    }
                }

                // Если нашли 3 коэффициента — это полный матч 1X2
                if (odds.size() == 3) break;

                // Если встретили название команды (не коэффициент) — стоп
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

            // Формируем исходы — теперь 3 исхода если есть 3 коэффициента
            List<RawEvent.RawOutcome> outcomes = new ArrayList<>();
            if (odds.size() >= 3) {
                outcomes.add(new RawEvent.RawOutcome("П1", odds.get(0)));
                outcomes.add(new RawEvent.RawOutcome("Х", odds.get(1)));
                outcomes.add(new RawEvent.RawOutcome("П2", odds.get(2)));
            } else {
                // Для тенниса/баскетбола — только 2 исхода (без ничьей)
                outcomes.add(new RawEvent.RawOutcome("П1", odds.get(0)));
                outcomes.add(new RawEvent.RawOutcome("П2", odds.get(1)));
            }

            List<RawEvent.RawMarket> markets = List.of(
                    new RawEvent.RawMarket("1X2", outcomes));

            String dateKey = LocalDateTime.now().toLocalDate().toString(); // 2026-08-12
            String eventId = "w_" + team1.toLowerCase().replaceAll("\\s+", "_")
                    + "_" + team2.toLowerCase().replaceAll("\\s+", "_")
                    + "_" + dateKey;

            // Ссылка ведёт на поиск Winline по названию матча — там сразу виден нужный матч
            String searchQuery = team1 + " " + team2;
            String eventUrl = BASE_URL + "/line?search="
                    + java.net.URLEncoder.encode(searchQuery, java.nio.charset.StandardCharsets.UTF_8);

            events.add(new RawEvent(
                    eventId, "Sport", "Winline",
                    team1, team2,
                    LocalDateTime.now().plusHours(2),
                    markets,
                    eventUrl
            ));

            log.debug("[Winline] Найден матч: {} vs {} | {}", team1, team2, odds);

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
        // Убираем лишнее: время, "Сегодня", "Завтра", цифры
        return name
                .replaceAll("\\bСегодня\\b", "")
                .replaceAll("\\bЗавтра\\b", "")
                .replaceAll("\\b\\d{2}:\\d{2}\\b", "")
                .replaceAll("\\b\\d+\\b", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}