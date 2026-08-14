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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WinlineParser extends AbstractBookmakerParser {

    private static final String BASE_URL = "https://winline.ru";

    // Корневые разделы для старта
    private static final List<String> SPORT_ROOT_PATHS = List.of(
            "/stavki/sport/futbol",
            "/stavki/sport/hokkey",
            "/stavki/sport/tennis",
            "/stavki/sport/basketbol",
            "/stavki/sport/voleybol",
            "/stavki/sport/kibersport",
            "/stavki/sport/mma",
            "/stavki/sport/boks"
    );

    // Стоп-слова для фильтрации мусорных ссылок
    private static final List<String> STOP_WORDS = List.of(
            "help", "support", "blog", "bonus", "rules", "about", "contacts",
            "promotions", "news", "faq", "terms", "privacy", "login", "register",
            "pomosh", "podderzhka", "akcii", "pravila", "kontakty", "novosti", "voprosy"
    );

    private static final int PARALLELISM = 5;
    private static final int GLOBAL_TIMEOUT_SECONDS = 240;

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
        long startTime = System.currentTimeMillis();
        log.info("[Winline] Запуск парсера (Virtual Threads: {}, Roots: {})", PARALLELISM, SPORT_ROOT_PATHS.size());

        Map<String, RawEvent> uniqueEventsMap = new ConcurrentHashMap<>();
        Semaphore semaphore = new Semaphore(PARALLELISM);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (String rootPath : SPORT_ROOT_PATHS) {
                CompletableFuture<Void> rootFuture = CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            processSportSection(rootPath, uniqueEventsMap, semaphore);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("[Winline] Критическая ошибка в разделе {}: {}", rootPath, e.getMessage());
                    }
                }, executor);
                futures.add(rootFuture);
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(GLOBAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Winline] Глобальный таймаут или ошибка ожидания: {}", e.getMessage());
            }

        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        List<RawEvent> resultList = new ArrayList<>(uniqueEventsMap.values());

        log.info("[Winline] Парсинг завершен за {} мс. Уникальных событий: {}", duration, resultList.size());

        if (!resultList.isEmpty()) {
            eventRepository.saveEvents("WINLINE", resultList);
            Set<String> activeExternalIds = resultList.stream().map(RawEvent::externalId).collect(Collectors.toSet());
            eventRepository.markInactiveEvents("WINLINE", activeExternalIds);
            log.info("[Winline] СОХРАНЕНО {} событий", resultList.size());
        } else {
            log.warn("[Winline] Не найдено событий");
        }

        return resultList;
    }

    private void processSportSection(String rootPath, Map<String, RawEvent> globalEventsMap, Semaphore semaphore) {
        String rootUrl = BASE_URL + rootPath;
        log.debug("[Winline] Анализ раздела: {}", rootUrl);

        List<String> leagueUrls = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(createLaunchOptions());
            BrowserContext context = browser.newContext(createContextOptions());
            Page page = context.newPage();
            addStealthScripts(page);

            try (browser; context; page) {
                page.navigate(rootUrl, new Page.NavigateOptions().setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                closePopups(page);

                // Пытаемся закрыть модальные окна (cookies, geo, etc)
                closeModals(page);

                page.waitForTimeout(3000); // Ждем рендеринга

                // Парсим корень
                List<RawEvent> rootEvents = parseCurrentPage(page, rootUrl);
                addToGlobalMap(globalEventsMap, rootEvents);

                // Ищем лиги
                leagueUrls = extractLeagueUrls(page, rootPath);
                log.info("[Winline] В разделе {} найдено {} лиг для парсинга", rootPath, leagueUrls.size());

            } catch (Exception e) {
                log.warn("[Winline] Ошибка при анализе корня {}: {}", rootPath, e.getMessage());
            }
        }

        // Парсим найденные лиги параллельно
        if (!leagueUrls.isEmpty()) {
            List<CompletableFuture<Void>> leagueFutures = new ArrayList<>();
            for (String leagueUrl : leagueUrls) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            Thread.sleep(1000 + new Random().nextInt(2000));
                            parseSingleUrl(leagueUrl, globalEventsMap);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.warn("[Winline] Ошибка лиги {}: {}", leagueUrl, e.getMessage());
                    }
                }, Executors.newVirtualThreadPerTaskExecutor());
                leagueFutures.add(future);
            }
            try {
                CompletableFuture.allOf(leagueFutures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                log.warn("[Winline] Ошибка ожидания лиг для {}: {}", rootPath, e.getMessage());
            }
        }
    }

    private void parseSingleUrl(String url, Map<String, RawEvent> globalEventsMap) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(createLaunchOptions());
            BrowserContext context = browser.newContext(createContextOptions());
            Page page = context.newPage();
            addStealthScripts(page);

            try (browser; context; page) {
                log.debug("[Winline] Парсинг лиги: {}", url);
                page.navigate(url, new Page.NavigateOptions().setTimeout(45_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                closeModals(page);
                page.waitForTimeout(2000);

                List<RawEvent> events = parseCurrentPage(page, url);
                addToGlobalMap(globalEventsMap, events);
            } catch (Exception e) {
                log.warn("[Winline] Ошибка страницы {}: {}", url, e.getMessage());
            }
        }
    }

    private void addToGlobalMap(Map<String, RawEvent> map, List<RawEvent> events) {
        for (RawEvent event : events) {
            map.put(event.externalId(), event);
        }
    }

    private BrowserType.LaunchOptions createLaunchOptions() {
        return new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--disable-blink-features=AutomationControlled", "--no-sandbox", "--disable-dev-shm-usage"));
    }

    private Browser.NewContextOptions createContextOptions() {
        return new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .setLocale("ru-RU")
                .setTimezoneId("Europe/Moscow")
                .setViewportSize(1920, 1080);
    }

    private void addStealthScripts(Page page) {
        page.addInitScript("""
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru', 'en'] });
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
            window.chrome = { runtime: {} };
        """);
    }

    /**
     * Пытается закрыть модальные окна (cookies, geo, age)
     */
    private void closeModals(Page page) {
        try {
            // Типичные селекторы для закрытия попапов на Winline
            String[] closeSelectors = {
                    "button:has-text('Принять')",
                    "button:has-text('OK')",
                    "button:has-text('Закрыть')",
                    ".modal-close",
                    ".popup-close",
                    "button[aria-label='Close']"
            };

            for (String selector : closeSelectors) {
                try {
                    if (page.isVisible(selector)) {
                        page.click(selector, new Page.ClickOptions().setTimeout(2000));
                        log.debug("[Winline] Закрыто модальное окно: {}", selector);
                        page.waitForTimeout(500);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("[Winline] Ошибка при закрытии модалок: {}", e.getMessage());
        }
    }

    /**
     * Извлекает ссылки на лиги, фильтруя мусор
     */
    @SuppressWarnings("unchecked")
    private List<String> extractLeagueUrls(Page page, String rootPath) {
        List<String> urls = new ArrayList<>();
        try {
            // Собираем все ссылки
            List<String> rawUrls = (List<String>) page.evaluate("""
                () => {
                    return Array.from(document.querySelectorAll('a[href]'))
                        .map(a => a.href);
                }
                """);

            String baseUrlPrefix = BASE_URL + rootPath;

            for (String url : rawUrls) {
                // 1. Должна начинаться с корня текущего спорта
                if (!url.startsWith(baseUrlPrefix)) continue;

                // 2. Не должна быть самим корнем
                String cleanUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                String cleanRoot = baseUrlPrefix.endsWith("/") ? baseUrlPrefix.substring(0, baseUrlPrefix.length() - 1) : baseUrlPrefix;
                if (cleanUrl.equals(cleanRoot)) continue;

                // 3. Фильтр по стоп-словам (чтобы не кликать по "Помощи", "Блогу" и т.д.)
                String lowerUrl = url.toLowerCase();
                boolean isStop = STOP_WORDS.stream().anyMatch(lowerUrl::contains);
                if (isStop) continue;

                urls.add(cleanUrl);
            }

            return new ArrayList<>(new LinkedHashSet<>(urls));
        } catch (Exception e) {
            log.warn("[Winline] Ошибка поиска лиг: {}", e.getMessage());
        }
        return urls;
    }

    private List<RawEvent> parseCurrentPage(Page page, String url) {
        List<RawEvent> events = new ArrayList<>();
        try {
            try {
                page.waitForSelector("text=/\\d+\\.\\d{2}/", new Page.WaitForSelectorOptions().setTimeout(15_000));
            } catch (Exception e) {
                return events;
            }

            autoScroll(page);
            page.waitForTimeout(2000);

            List<EventLink> eventLinks = extractEventLinks(page);
            String pageText = page.innerText("body");

            if (pageText != null && !pageText.isEmpty()) {
                events = parsePageText(pageText, eventLinks);
                if (!events.isEmpty()) {
                    log.debug("[Winline] На {} найдено {} событий", url, events.size());
                }
            }
        } catch (Exception e) {
            log.warn("[Winline] Ошибка парсинга {}: {}", url, e.getMessage());
        }
        return events;
    }

    private void autoScroll(Page page) {
        page.evaluate("""
            async () => {
                await new Promise((resolve) => {
                    let totalHeight = 0;
                    const distance = 1000;
                    const timer = setInterval(() => {
                        window.scrollBy(0, distance);
                        totalHeight += distance;
                        if (totalHeight >= document.body.scrollHeight) {
                            clearInterval(timer);
                            window.scrollTo(0, 0);
                            resolve();
                        }
                    }, 100);
                });
            }
        """);
    }

    @SuppressWarnings("unchecked")
    private List<EventLink> extractEventLinks(Page page) {
        List<EventLink> result = new ArrayList<>();
        try {
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
            log.warn("[Winline] Ошибка ссылок: {}", e.getMessage());
        }
        return result;
    }

    private String findEventUrl(String team1, String team2, List<EventLink> eventLinks) {
        String t1 = team1.toLowerCase();
        String t2 = team2.toLowerCase();
        EventLink best = null;
        for (EventLink link : eventLinks) {
            String lower = link.text().toLowerCase();
            if (lower.contains(t1) && lower.contains(t2)) {
                if (best == null || link.text().length() < best.text().length()) best = link;
            }
        }
        if (best != null) {
            String eventId = extractEventId(best.href());
            if (eventId != null) return BASE_URL + "/stavki/event/" + eventId;
        }
        return BASE_URL + "/stavki/sport/futbol";
    }

    private String extractEventId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("/(\\d+)$").matcher(url);
        return m.find() ? m.group(1) : null;
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
                if (oddLine.matches(".*[А-Яа-яA-Za-z]{4,}.*") && !oddLine.matches(".*\\d+\\.\\d{2}.*") && odds.size() >= 2) break;
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

            List<RawEvent.RawMarket> markets = List.of(new RawEvent.RawMarket("1X2", outcomes));
            String dateKey = LocalDateTime.now().toLocalDate().toString();
            String eventId = "w_" + team1.toLowerCase().replaceAll("\\s+", "_") + "_" + team2.toLowerCase().replaceAll("\\s+", "_") + "_" + dateKey;
            String eventUrl = findEventUrl(team1, team2, eventLinks);

            events.add(new RawEvent(eventId, "Sport", "Winline", team1, team2, LocalDateTime.now().plusHours(2), markets, eventUrl));
            i = lastOddIdx;
        }
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
        return name.replaceAll("\\bСегодня\\b", "").replaceAll("\\bЗавтра\\b", "")
                .replaceAll("\\b\\d{2}:\\d{2}\\b", "").replaceAll("\\b\\d+\\b", "")
                .replaceAll("\\s+", " ").trim();
    }

    private void closePopups(Page page) {
        try {
            // Пытаемся закрыть куки/баннеры
            String[] closeSelectors = {
                    "button:has-text('Принять')",
                    "button:has-text('OK')",
                    "button:has-text('Понятно')",
                    ".popup-close",
                    ".modal-close",
                    "button[aria-label='Close']",
                    ".icon-close"
            };

            for (String selector : closeSelectors) {
                try {
                    if (page.isVisible(selector)) {
                        page.click(selector);
                        log.info("[Winline] Закрыто всплывающее окно: {}", selector);
                        page.waitForTimeout(1000);
                    }
                } catch (Exception ignored) {}
            }

            // Если окно все еще есть, пробуем кликнуть по затемнению (backdrop), чтобы закрыть
            try {
                if (page.isVisible(".cdk-overlay-backdrop")) {
                    page.click(".cdk-overlay-backdrop", new Page.ClickOptions().setPosition(10, 10)); // Клик в угол
                    page.waitForTimeout(1000);
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            log.debug("[Winline] Не удалось закрыть попапы (возможно, их нет)");
        }
    }
}