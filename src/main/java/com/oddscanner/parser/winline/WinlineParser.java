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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WinlineParser extends AbstractBookmakerParser {

    private static final String BASE_URL = "https://winline.ru";

    private static final List<String> SPORT_PATHS = List.of(
            "/stavki/sport/futbol",
            "/stavki/sport/hokkey",
            "/stavki/sport/tennis",
            "/stavki/sport/basketbol",
            "/stavki/sport/voleybol",
            "/stavki/sport/kibersport",
            "/stavki/sport/mma",
            "/stavki/sport/boks"
    );

    private static final List<String> KNOWN_LEAGUES = List.of(
            "/stavki/sport/futbol/angliya/premer-liga",
            "/stavki/sport/futbol/ispaniya/primera",
            "/stavki/sport/futbol/italiya/seriya-a",
            "/stavki/sport/futbol/germaniya/bundesliga",
            "/stavki/sport/futbol/franciya/liga-1",
            "/stavki/sport/futbol/rossiya/premer-liga",
            "/stavki/sport/futbol/niderlandy/eredivisie",
            "/stavki/sport/futbol/portugaliya/primeira-liga",
            "/stavki/sport/futbol/angliya/chempionship",
            "/stavki/sport/futbol/ispaniya/segunda",
            "/stavki/sport/futbol/italiya/seriya-b",
            "/stavki/sport/futbol/germaniya/2-bundesliga",
            "/stavki/sport/futbol/franciya/liga-2",
            "/stavki/sport/futbol/evropa/liga-chempionov",
            "/stavki/sport/futbol/evropa/liga-evropy",
            "/stavki/sport/futbol/evropa/liga-konferenciy",
            "/stavki/sport/futbol/turciya/super-liga",
            "/stavki/sport/futbol/belgiya/liga-zhupile",
            "/stavki/sport/futbol/avstriya/bundesliga",
            "/stavki/sport/futbol/shveycariya/super-liga",
            "/stavki/sport/futbol/greciya/super-liga",
            "/stavki/sport/futbol/serbiya/super-liga",
            "/stavki/sport/futbol/chexiya/1-liga",
            "/stavki/sport/futbol/ukraina/premer-liga",
            "/stavki/sport/hokkey/ssha/nhl",
            "/stavki/sport/hokkey/rossiya/khl",
            "/stavki/sport/hokkey/chehiya/extraliga",
            "/stavki/sport/hokkey/finlyandiya/liiga",
            "/stavki/sport/hokkey/shveciya/shl",
            "/stavki/sport/basketbol/ssha/nba",
            "/stavki/sport/basketbol/evropa/evroliga",
            "/stavki/sport/basketbol/evropa/evrokubok",
            "/stavki/sport/basketbol/rossiya/edinaya-liga-vtb",
            "/stavki/sport/basketbol/ispaniya/liga-akb",
            "/stavki/sport/basketbol/italiya/seriya-a",
            "/stavki/sport/basketbol/germaniya/bbl",
            "/stavki/sport/tennis/atp",
            "/stavki/sport/tennis/wta",
            "/stavki/sport/tennis/bolshoy-shlem",
            "/stavki/sport/tennis/kubok-devisa",
            "/stavki/sport/voleybol/rossiya/superliga",
            "/stavki/sport/voleybol/italiya/seriya-a",
            "/stavki/sport/voleybol/polsha/plus-liga",
            "/stavki/sport/mma/ufc",
            "/stavki/sport/mma/bellator",
            "/stavki/sport/mma/one-championship",
            "/stavki/sport/boks/professionalnyy-boks"
    );

    private static final int PARALLELISM = 5;

    private record EventLink(String href, String text) {
    }

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
        log.info("[Winline] Запуск парсера (Parallel browsers: {})", PARALLELISM);

        Map<String, RawEvent> uniqueEventsMap = new ConcurrentHashMap<>();

        // ШАГ 1: Собираем все URL (в одном браузере, последовательно)
        Set<String> allUrls = collectAllUrls();

        log.info("[Winline] Всего URL для парсинга: {}", allUrls.size());

        // ШАГ 2: Парсим параллельно - КАЖДЫЙ ПОТОК СО СВОИМ БРАУЗЕРОМ
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Semaphore semaphore = new Semaphore(PARALLELISM);

        for (String url : allUrls) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        parseUrlParallel(url, uniqueEventsMap);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.debug("[Winline] Ошибка {}: {}", url, e.getMessage());
                }
            }, executor);
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Winline] Таймаут или ошибка ожидания: {}", e.getMessage());
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        long duration = System.currentTimeMillis() - startTime;
        List<RawEvent> resultList = new ArrayList<>(uniqueEventsMap.values());

        log.info("[Winline] Парсинг завершен за {} мс. Уникальных событий: {}", duration, resultList.size());

        if (!resultList.isEmpty()) {
            eventRepository.saveEvents("WINLINE", resultList);
            Set<String> activeExternalIds = resultList.stream()
                    .map(RawEvent::externalId)
                    .collect(Collectors.toSet());
            eventRepository.markInactiveEvents("WINLINE", activeExternalIds);
            log.info("[Winline] СОХРАНЕНО {} событий", resultList.size());
        }

        return resultList;
    }

    private Set<String> collectAllUrls() {
        Set<String> allUrls = new LinkedHashSet<>();
        Set<String> discoveredLeagues = new HashSet<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(createLaunchOptions());

            try (browser) {
                for (String sportPath : SPORT_PATHS) {
                    try {
                        String url = BASE_URL + sportPath;
                        log.info("[Winline] Сбор лиг с: {}", url);

                        try (BrowserContext context = browser.newContext(createContextOptions());
                             Page page = context.newPage()) {

                            addStealthScripts(page);
                            page.navigate(url, new Page.NavigateOptions()
                                    .setTimeout(15_000)
                                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                            closePopups(page);
                            Thread.sleep(300);
                            autoScroll(page);
                            Thread.sleep(200);

                            List<String> discovered = discoverLeagues(page, sportPath);
                            discoveredLeagues.addAll(discovered);

                            // Парсим события с корневой
                            List<RawEvent> events = parseCurrentPage(page);
                            addToGlobalMap(new HashMap<>(), events);
                            log.info("[Winline] На корневой {} найдено {} событий", sportPath, events.size());
                        }
                    } catch (Exception e) {
                        log.warn("[Winline] Ошибка сбора лиг с {}: {}", sportPath, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Winline] Ошибка сбора URL: {}", e.getMessage());
        }

        for (String sportPath : SPORT_PATHS) {
            allUrls.add(BASE_URL + sportPath);
        }
        allUrls.addAll(discoveredLeagues);
        for (String leaguePath : KNOWN_LEAGUES) {
            allUrls.add(BASE_URL + leaguePath);
        }

        log.info("[Winline] Собрано URL: корневых {}, динамических {}, известных {}, всего {}",
                SPORT_PATHS.size(), discoveredLeagues.size(), KNOWN_LEAGUES.size(), allUrls.size());

        return allUrls;
    }

    private void parseUrlParallel(String url, Map<String, RawEvent> globalEventsMap) {
        // КАЖДЫЙ ПОТОК СОЗДАЕТ СВОЙ PLAYWRIGHT И БРАУЗЕР
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(createLaunchOptions());
            try (BrowserContext context = browser.newContext(createContextOptions());
                 Page page = context.newPage()) {

                addStealthScripts(page);

                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(15_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                closePopups(page);
                Thread.sleep(200);

                autoScroll(page);
                Thread.sleep(100);

                List<RawEvent> events = parseCurrentPage(page);

                if (!events.isEmpty()) {
                    log.debug("[Winline] {} -> {} событий", url, events.size());
                }

                addToGlobalMap(globalEventsMap, events);

            } catch (Exception e) {
                log.debug("[Winline] Ошибка {}: {}", url, e.getMessage());
            }
        } catch (Exception e) {
            log.debug("[Winline] Ошибка создания браузера для {}: {}", url, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> discoverLeagues(Page page, String rootPath) {
        List<String> leagues = new ArrayList<>();

        try {
            List<String> rawUrls = (List<String>) page.evaluate("""
                    () => {
                        const links = Array.from(document.querySelectorAll('a[href]'));
                        const result = [];
                        const currentUrl = window.location.href;
                        
                        for (const a of links) {
                            const href = a.href;
                            if (!href) continue;
                            
                            if (href.includes('/stavki/sport/') && 
                                !href.includes('/event/') &&
                                !href.endsWith('/') &&
                                href !== currentUrl) {
                                
                                const parts = href.split('/');
                                if (parts.length >= 6) {
                                    result.push(href);
                                }
                            }
                        }
                        return result;
                    }
                    """);

            for (String url : rawUrls) {
                if (!leagues.contains(url)) {
                    leagues.add(url);
                }
            }

            log.info("[Winline] Найдено {} лиг на {}", leagues.size(), rootPath);

        } catch (Exception e) {
            log.warn("[Winline] Ошибка поиска лиг: {}", e.getMessage());
        }

        return leagues;
    }

    private void addToGlobalMap(Map<String, RawEvent> map, List<RawEvent> events) {
        for (RawEvent event : events) {
            map.putIfAbsent(event.externalId(), event);
        }
    }

    private BrowserType.LaunchOptions createLaunchOptions() {
        return new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-gpu"
                ));
    }

    private Browser.NewContextOptions createContextOptions() {
        return new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
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

    private void closePopups(Page page) {
        try {
            String[] closeSelectors = {
                    "button:has-text('Принять')",
                    "button:has-text('OK')",
                    "button:has-text('Понятно')",
                    "button:has-text('Закрыть')",
                    ".popup-close",
                    ".modal-close",
                    "button[aria-label='Close']",
                    ".icon-close"
            };

            for (String selector : closeSelectors) {
                try {
                    if (page.isVisible(selector)) {
                        page.click(selector, new Page.ClickOptions().setTimeout(1000));
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) {
                }
            }

            try {
                if (page.isVisible(".cdk-overlay-backdrop")) {
                    page.click(".cdk-overlay-backdrop", new Page.ClickOptions().setPosition(10, 10));
                    Thread.sleep(100);
                }
            } catch (Exception ignored) {
            }

        } catch (Exception e) {
            log.debug("[Winline] Не удалось закрыть попапы");
        }
    }

    private List<RawEvent> parseCurrentPage(Page page) {

        List<RawEvent> events = new ArrayList<>();

        try {
            try {
                page.waitForSelector("text=/\\d+\\.\\d{2}/", new Page.WaitForSelectorOptions().setTimeout(4_000));
            } catch (Exception e) {
                return events;
            }

            List<EventLink> eventLinks = extractEventLinks(page, page.url());

            String pageText = page.innerText("body");
            if (pageText != null && !pageText.isEmpty()) {
                events = parsePageText(pageText, eventLinks);
            }

        } catch (Exception e) {
            log.debug("[Winline] Ошибка парсинга {}: {}", page.url(), e.getMessage());
        }

        return events;
    }

    @SuppressWarnings("unchecked")
    private List<EventLink> extractEventLinks(Page page, String pageUrl) {

        List<EventLink> result = new ArrayList<>();

        try {
            List<Map<String, String>> raw = (List<Map<String, String>>) page.evaluate("""
                    () => {
                        const eventIdRegex = /\\/(\\d+)$/;

                        function findLeagueHref(anchor) {
                            let node = anchor.closest('li, tr, div, section') || anchor.parentElement;
                            for (let depth = 0; depth < 6 && node; depth++) {
                                const candidates = Array.from(
                                    node.querySelectorAll('a[href*="/stavki/sport/"]')
                                );
                                for (const c of candidates) {
                                    if (c === anchor) continue;
                                    if (!c.href.includes('/event/')) {
                                        return c.href.replace(/\\/$/, '');
                                    }
                                }
                                node = node.parentElement;
                            }
                            return null;
                        }

                        const anchors = Array.from(document.querySelectorAll('a[href]'));
                        const out = [];

                        for (const a of anchors) {
                            const href = a.href;
                            const text = (a.innerText || '').replace(/\\s+/g, ' ').trim();

                            if (!href || !text || text.length <= 2) continue;
                            if (!eventIdRegex.test(href)) continue;
                            if (!href.includes('/stavki/') && !href.includes('/live/')) continue;

                            const leagueHref = findLeagueHref(a);
                            if (leagueHref) {
                                const id = href.match(eventIdRegex)[1];
                                const finalHref = leagueHref + '/' + id;
                                out.push({ href: finalHref, text: text });
                            } else {
                                out.push({ href: href, text: text });
                            }
                        }

                        return out;
                    }
                    """);

            for (Map<String, String> m : raw) {
                String href = m.get("href");
                String text = m.get("text");
                if (href != null && text != null) {
                    result.add(new EventLink(href, text));
                }
            }

        } catch (Exception e) {
            log.debug("[Winline] Ошибка извлечения ссылок: {}", e.getMessage());
        }

        return result;
    }

    private String findEventUrl(String team1, String team2, List<EventLink> eventLinks) {

        String t1 = team1.toLowerCase().trim();
        String t2 = team2.toLowerCase().trim();

        for (EventLink link : eventLinks) {
            String lower = link.text().toLowerCase();
            if (lower.contains(t1) && lower.contains(t2)) {
                return link.href();
            }
        }

        String[] t1Parts = t1.split("\\s+");
        String[] t2Parts = t2.split("\\s+");

        for (EventLink link : eventLinks) {
            String lower = link.text().toLowerCase();
            boolean hasT1 = Arrays.stream(t1Parts).anyMatch(part -> part.length() > 2 && lower.contains(part));
            boolean hasT2 = Arrays.stream(t2Parts).anyMatch(part -> part.length() > 2 && lower.contains(part));
            if (hasT1 && hasT2) {
                return link.href();
            }
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
            if (!line1.matches(".*[А-Яа-яA-Za-z]{3,}.*") || !line2.matches(".*[А-Яа-яA-Za-z]{3,}.*")) continue;
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

                if (oddLine.matches(".*[А-Яа-яA-Za-z]{4,}.*")
                        && !oddLine.matches(".*\\d+\\.\\d{2}.*")
                        && odds.size() >= 2) {
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

            String eventUrl = findEventUrl(team1, team2, eventLinks);

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

            LocalDateTime eventTime = parseEventTime(text, team1, team2);

            String dateKey = eventTime.toLocalDate().toString();
            String eventId = "w_"
                    + team1.toLowerCase().replaceAll("\\s+", "_")
                    + "_"
                    + team2.toLowerCase().replaceAll("\\s+", "_")
                    + "_"
                    + dateKey;

            events.add(new RawEvent(
                    eventId,
                    "Sport",
                    "Winline",
                    team1,
                    team2,
                    eventTime,
                    markets,
                    eventUrl != null ? eventUrl : ""
            ));

            i = lastOddIdx;
        }

        return events;
    }

    private LocalDateTime parseEventTime(String pageText, String team1, String team2) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        String[] lines = pageText.split("\\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim().toLowerCase();

            Matcher timeMatcher = Pattern.compile("(\\d{1,2}):(\\d{2})(?::\\d{2})?").matcher(line);

            if (timeMatcher.find()) {
                int hour = Integer.parseInt(timeMatcher.group(1));
                int minute = Integer.parseInt(timeMatcher.group(2));

                String lowerTeam1 = team1.toLowerCase();
                String lowerTeam2 = team2.toLowerCase();

                int start = Math.max(0, i - 3);
                int end = Math.min(lines.length - 1, i + 3);
                for (int j = start; j <= end; j++) {
                    String context = lines[j].toLowerCase();
                    if (context.contains(lowerTeam1.substring(0, Math.min(3, lowerTeam1.length()))) ||
                            context.contains(lowerTeam2.substring(0, Math.min(3, lowerTeam2.length())))) {
                        LocalTime time = LocalTime.of(hour, minute);
                        LocalDateTime result = LocalDateTime.of(today, time);
                        if (result.isBefore(now)) {
                            result = result.plusDays(1);
                        }
                        return result;
                    }
                }
            }
        }

        log.debug("[Winline] Не найдено время для {} vs {}, используем fallback", team1, team2);
        return now.plusHours(2);
    }

    private void autoScroll(Page page) {
        page.evaluate("""
                async () => {
                    await new Promise((resolve) => {
                        let totalHeight = 0;
                        const distance = 500;
                        const timer = setInterval(() => {
                            window.scrollBy(0, distance);
                            totalHeight += distance;
                            if (totalHeight >= document.body.scrollHeight) {
                                clearInterval(timer);
                                window.scrollTo(0, 0);
                                resolve();
                            }
                        }, 80);
                    });
                }
                """);
    }

    private boolean isServiceLine(String line) {
        Set<String> serviceWords = Set.of(
                "линия", "live", "сейчас", "игры", "киберспорт", "войти",
                "регистрация", "избранное", "ближайшие", "футбол", "теннис",
                "баскетбол", "хоккей", "волейбол", "мма", "бокс", "главные",
                "события", "матч", "дня", "популярные", "результаты", "о нас",
                "мой счет", "winline", "support", "онлайн", "чат", "работа",
                "игрокам", "программа", "лояльности", "служба", "поддержки",
                "клубы", "блог", "документы", "основные", "перейти", "вернуться",
                "главную", "страница", "не найдена", "исход", "тотал", "фора",
                "обе", "забьют"
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