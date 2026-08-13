package com.oddscanner.parser.marathon;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MarathonParser extends AbstractBookmakerParser {

    private static final String BASE_URL = "https://www.marathonbet.ru";
    private static final String FOOTBALL_BASE_URL = BASE_URL + "/su/popular/Football+-+11";

    // === НАСТРОЙКИ ===
    private static final int MAX_PAGES = 30;       // Глубина парсинга
    private static final int PARALLELISM = 6;      // Параллельность (6 потоков для стабильности)
    private static final int MAX_RETRIES = 2;      // Количество повторов при ошибке загрузки

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern TIME_PATTERN = Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^\\d+(?:[.,]\\d+)?$");

    public MarathonParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        super(meterRegistry, eventRepository);
    }

    @Override
    public String getName() {
        return "Marathon";
    }

    @Override
    public List<RawEvent> doParse() throws Exception {
        long startTime = System.currentTimeMillis();
        log.info("[Marathon] Запуск парсера (Virtual Threads: {}, Pages: {})", PARALLELISM, MAX_PAGES);

        // Используем Map для дедупликации: Key = externalId, Value = RawEvent
        // Это позволяет перезаписывать старые данные новыми (если событие встретилось дважды)
        Map<String, RawEvent> uniqueEventsMap = new LinkedHashMap<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
                final int currentPage = pageNum;

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    // КАЖДЫЙ ПОТОК СОЗДАЕТ СВОЙ ИЗОЛИРОВАННЫЙ БРАУЗЕР
                    try (Playwright playwright = Playwright.create()) {
                        Browser browser = playwright.chromium().launch(
                                new BrowserType.LaunchOptions()
                                        .setHeadless(true)
                                        .setArgs(List.of(
                                                "--disable-blink-features=AutomationControlled",
                                                "--no-sandbox",
                                                "--disable-dev-shm-usage",
                                                "--single-process" // Экономия памяти
                                        ))
                        );

                        BrowserContext context = browser.newContext(
                                new Browser.NewContextOptions()
                                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                                        .setLocale("ru-RU")
                                        .setTimezoneId("Europe/Moscow")
                                        .setViewportSize(1920, 1080)
                        );

                        Page page = context.newPage();
                        page.addInitScript("""
                                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                                Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru', 'en'] });
                                window.chrome = { runtime: {} };
                                """);

                        String url = currentPage == 1 ? FOOTBALL_BASE_URL : FOOTBALL_BASE_URL + "?page=" + currentPage;

                        // Загрузка с ретраями
                        String html = loadPageWithRetry(page, url, currentPage);

                        // Закрываем ресурсы сразу после получения HTML
                        page.close();
                        context.close();
                        browser.close();

                        if (html != null && !html.isBlank()) {
                            List<RawEvent> pageEvents = parseHtml(html);

                            // Синхронизированное добавление в общую карту
                            // put перезаписывает значение, если ключ уже есть (оставляем последнее/свежее)
                            synchronized (uniqueEventsMap) {
                                for (RawEvent event : pageEvents) {
                                    uniqueEventsMap.put(event.externalId(), event);
                                }
                            }
                            log.info("[Marathon] Страница {}: {} событий загружено", currentPage, pageEvents.size());
                        } else {
                            log.warn("[Marathon] Страница {}: пустой HTML", currentPage);
                        }

                    } catch (Exception e) {
                        log.error("[Marathon] Критическая ошибка в потоке страницы {}", currentPage, e);
                    }
                }, executor);

                futures.add(future);
            }

            // Ждем завершения всех задач
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        long duration = System.currentTimeMillis() - startTime;
        List<RawEvent> resultList = new ArrayList<>(uniqueEventsMap.values());

        log.info("[Marathon] Парсинг завершен за {} мс. Уникальных событий: {}", duration, resultList.size());

        if (resultList.isEmpty()) {
            log.warn("[Marathon] События не найдены");
            return Collections.emptyList();
        }

        eventRepository.saveEvents("MARATHON", resultList);

        Set<String> activeExternalIds = resultList.stream()
                .map(RawEvent::externalId)
                .collect(Collectors.toSet());

        eventRepository.markInactiveEvents("MARATHON", activeExternalIds);

        log.info("[Marathon] СОХРАНЕНО {} событий", resultList.size());
        return resultList;
    }

    /**
     * Загрузка страницы с повторными попытками
     */
    private String loadPageWithRetry(Page page, String url, int pageNum) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return loadPage(page, url);
            } catch (Exception e) {
                log.warn("[Marathon] Страница {} (попытка {}): ошибка - {}", pageNum, attempt, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    log.error("[Marathon] Страница {}: провал после {} попыток", pageNum, MAX_RETRIES);
                    return null;
                }
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    private String loadPage(Page page, String url) {
        page.navigate(url, new Page.NavigateOptions()
                .setTimeout(45_000) // Таймаут 45 сек
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

        // Рандомная задержка для имитации человека (1.5 - 2.5 сек)
        page.waitForTimeout(1500 + new Random().nextInt(1000));

        // Быстрый скролл для подгрузки динамического контента
        for (int i = 0; i < 5; i++) {
            page.mouse().wheel(0, 2000);
            page.waitForTimeout(100);
        }

        page.waitForTimeout(500);
        return page.content();
    }

    // ======================== HTML PARSING ========================

    private List<RawEvent> parseHtml(String html) {
        List<RawEvent> result = new ArrayList<>();
        Document document = Jsoup.parse(html);

        Elements eventBlocks = document.select("[data-event-eventid]");
        if (eventBlocks.isEmpty()) {
            eventBlocks = document.select(".coupon-row");
        }

        Set<String> processedEventIds = new HashSet<>();

        for (Element eventBlock : eventBlocks) {
            try {
                String eventId = firstNonBlank(
                        eventBlock.attr("data-event-eventid"),
                        eventBlock.attr("data-event-id"),
                        eventBlock.attr("data-id"));
                eventId = clean(eventId);

                if (eventId == null) eventId = buildFallbackEventId(eventBlock);
                if (eventId == null || !processedEventIds.add(eventId)) continue;

                String live = firstNonBlank(eventBlock.attr("data-live"), eventBlock.attr("data-event-live"));
                if ("true".equalsIgnoreCase(clean(live))) continue;

                RawEvent event = parseEvent(eventBlock, eventId);
                if (event != null) result.add(event);
            } catch (Exception e) {
                // Игнорируем ошибки парсинга конкретного блока
            }
        }
        return result;
    }

    private RawEvent parseEvent(Element eventBlock, String eventId) {
        String[] teams = extractTeams(eventBlock);
        if (teams == null) return null;
        LocalDateTime startsAt = extractStartTime(eventBlock);
        String league = extractLeague(eventBlock);
        List<RawEvent.RawMarket> markets = extractMarkets(eventBlock);
        if (markets.isEmpty()) return null;
        String eventUrl = extractEventUrl(eventBlock);
        String externalId = "marathon_" + eventId;
        return new RawEvent(externalId, "Футбол", league, teams[0], teams[1], startsAt, markets, eventUrl == null ? "" : eventUrl);
    }

    // ======================== TEAMS & META ========================

    private String[] extractTeams(Element eventBlock) {
        Elements members = eventBlock.select(".member-name .member-link");
        String[] result = extractFirstTwoTexts(members);
        if (result != null) return result;
        members = eventBlock.select(".member-name");
        result = extractFirstTwoTexts(members);
        if (result != null) return result;
        members = eventBlock.select("[data-member-name]");
        result = extractFirstTwoAttributes(members);
        if (result != null) return result;
        members = eventBlock.select(".member-link");
        result = extractFirstTwoTexts(members);
        if (result != null) return result;
        String eventName = firstNonBlank(eventBlock.attr("data-event-name"), eventBlock.attr("data-event-title"), eventBlock.attr("data-name"));
        result = splitTeams(clean(eventName));
        if (result != null) return result;
        return splitTeams(clean(eventBlock.text()));
    }

    private String[] extractFirstTwoTexts(Elements elements) {
        List<String> values = new ArrayList<>();
        for (Element el : elements) {
            String value = clean(el.text());
            if (value == null || parseOdds(value) != null) continue;
            if (!values.contains(value)) values.add(value);
            if (values.size() == 2) break;
        }
        return values.size() >= 2 ? new String[]{values.get(0), values.get(1)} : null;
    }

    private String[] extractFirstTwoAttributes(Elements elements) {
        List<String> values = new ArrayList<>();
        for (Element el : elements) {
            String value = clean(el.attr("data-member-name"));
            if (value == null) continue;
            if (!values.contains(value)) values.add(value);
            if (values.size() == 2) break;
        }
        return values.size() >= 2 ? new String[]{values.get(0), values.get(1)} : null;
    }

    private String[] splitTeams(String text) {
        if (text == null) return null;
        String[] separators = {" - ", " — ", " – ", " vs ", " VS ", " против "};
        for (String sep : separators) {
            int idx = text.indexOf(sep);
            if (idx <= 0) continue;
            String t1 = clean(text.substring(0, idx));
            String t2 = clean(text.substring(idx + sep.length()));
            if (t1 != null && t2 != null && t1.length() <= 150 && t2.length() <= 150) return new String[]{t1, t2};
        }
        return null;
    }

    private String extractEventUrl(Element eventBlock) {
        Element link = eventBlock.select("a[href]").stream()
                .filter(e -> { String href = clean(e.attr("href")); return href != null && (href.contains("/betting/") || href.contains("/su/")); })
                .findFirst().orElse(null);
        if (link == null) return null;
        String href = clean(link.attr("href"));
        if (href == null) return null;
        if (href.startsWith("http")) return href;
        return href.startsWith("/") ? BASE_URL + href : BASE_URL + "/" + href;
    }

    private LocalDateTime extractStartTime(Element eventBlock) {
        String dateText = clean(firstNonBlank(eventBlock.select(".date-wrapper").text(), eventBlock.select(".date").text(), eventBlock.select("[class*='date']").text()));
        LocalDate today = LocalDate.now();
        if (dateText == null) return today.atStartOfDay();
        Matcher matcher = TIME_PATTERN.matcher(dateText);
        if (!matcher.find()) return today.atStartOfDay();
        try {
            LocalTime time = LocalTime.parse(matcher.group(1), TIME_FORMAT);
            LocalDate date = dateText.toLowerCase(Locale.ROOT).contains("завтра") ? today.plusDays(1) : today;
            return LocalDateTime.of(date, time);
        } catch (DateTimeParseException e) { return today.atStartOfDay(); }
    }

    private String extractLeague(Element eventBlock) {
        String[] selectors = {".sport-category-name", ".category-name", ".name-field", ".category-link", "[class*='category']"};
        for (String sel : selectors) {
            Element el = eventBlock.select(sel).first();
            if (el == null) continue;
            String val = clean(el.text());
            if (val != null && val.length() < 200) return val;
        }
        String path = clean(firstNonBlank(eventBlock.attr("data-event-path"), eventBlock.attr("data-path")));
        if (path != null) {
            String[] parts = path.replace("+", " ").replace("%20", " ").split("/");
            if (parts.length >= 3) { String league = clean(parts[2]); if (league != null) return league; }
        }
        return "Футбол";
    }

    // ======================== MARKETS & OUTCOMES ========================

    private String inferMarketType(String rawName, List<RawEvent.RawOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return normalizeRawName(rawName);
        Set<String> names = outcomes.stream().map(o -> o.name().toLowerCase(Locale.ROOT).trim()).collect(Collectors.toSet());
        boolean hasHome = names.stream().anyMatch(n -> n.matches(".*(п1|home|team\\s*1|^1$).*"));
        boolean hasDraw = names.stream().anyMatch(n -> n.matches(".*(x|draw|ничья).*"));
        boolean hasAway = names.stream().anyMatch(n -> n.matches(".*(п2|away|team\\s*2|^2$).*"));
        if (hasHome && hasDraw && hasAway) return "1X2";
        boolean hasHandicapFormat = names.stream().anyMatch(n -> n.matches(".*\\([+-]?\\d+\\.?\\d*\\).*"));
        if (hasHandicapFormat) return "HANDICAP";
        boolean hasOver = names.stream().anyMatch(n -> n.contains("больше") || n.contains("over") || n.contains("тб") || n.matches(".*\\+\\d+.*"));
        boolean hasUnder = names.stream().anyMatch(n -> n.contains("меньше") || n.contains("under") || n.contains("тм") || n.matches(".*-\\d+.*"));
        if (hasOver && hasUnder) return "TOTAL_OVER_UNDER";
        if (outcomes.size() >= 2 && outcomes.size() <= 3) return "1X2";
        return normalizeRawName(rawName);
    }

    private String normalizeRawName(String rawName) {
        if (rawName == null) return "UNKNOWN";
        String lower = rawName.toLowerCase(Locale.ROOT);
        if (lower.matches(".*1.?x.?2.*") || lower.contains("исход матча")) return "1X2";
        if (lower.contains("тотал") || lower.contains("total")) return "TOTAL_OVER_UNDER";
        if (lower.contains("фора") || lower.contains("handicap")) return "HANDICAP";
        return rawName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private List<RawEvent.RawMarket> extractMarkets(Element eventBlock) {
        List<RawEvent.RawMarket> markets = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Elements selectionLinks = eventBlock.select("[data-selection-price]");
        if (!selectionLinks.isEmpty()) {
            Map<String, List<RawEvent.RawOutcome>> groupedByMarket = new LinkedHashMap<>();
            for (Element sel : selectionLinks) {
                String priceStr = clean(sel.attr("data-selection-price"));
                BigDecimal odds = parseOdds(priceStr);
                if (odds == null) continue;
                String selectionKey = sel.attr("data-selection-key");
                String outcomeName = extractOutcomeFromSelectionKey(selectionKey);
                if (outcomeName == null) outcomeName = findOutcomeText(sel);
                if (outcomeName == null) continue;
                String marketType = findParentAttr(sel);
                if (marketType == null) marketType = "RESULT";
                groupedByMarket.computeIfAbsent(marketType, k -> new ArrayList<>()).add(new RawEvent.RawOutcome(outcomeName, odds));
            }
            for (Map.Entry<String, List<RawEvent.RawOutcome>> entry : groupedByMarket.entrySet()) {
                List<RawEvent.RawOutcome> outcomes = deduplicateOutcomes(entry.getValue());
                if (outcomes.isEmpty()) continue;
                String inferredType = inferMarketType(entry.getKey(), outcomes);
                String key = inferredType.toLowerCase(Locale.ROOT);
                if (!processed.add(key)) continue;
                markets.add(new RawEvent.RawMarket(inferredType, outcomes));
            }
        }
        if (markets.isEmpty()) {
            Elements marketBlocks = eventBlock.select("[data-preference-id]");
            if (marketBlocks.isEmpty()) marketBlocks = eventBlock.select("[class*='market']");
            for (Element marketBlock : marketBlocks) {
                try {
                    Element parentMarket = marketBlock.parent();
                    if (parentMarket != null && parentMarket != eventBlock && parentMarket.hasAttr("data-preference-id")) continue;
                    String marketName = extractMarketName(marketBlock);
                    List<RawEvent.RawOutcome> outcomes = extractMarketOutcomes(marketBlock);
                    if (outcomes.isEmpty()) continue;
                    String inferredType = inferMarketType(marketName, outcomes);
                    String key = inferredType.trim().toLowerCase(Locale.ROOT);
                    if (!processed.add(key)) continue;
                    markets.add(new RawEvent.RawMarket(inferredType, outcomes));
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }
        return markets;
    }

    private String extractOutcomeFromSelectionKey(String selectionKey) {
        if (selectionKey == null || selectionKey.isBlank()) return null;
        int atIdx = selectionKey.lastIndexOf('@');
        if (atIdx < 0 || atIdx >= selectionKey.length() - 1) return null;
        String afterAt = selectionKey.substring(atIdx + 1);
        int dotIdx = afterAt.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx >= afterAt.length() - 1) return null;
        String marketPart = afterAt.substring(0, dotIdx);
        String outcomePart = afterAt.substring(dotIdx + 1);
        if ("Match_Result".equalsIgnoreCase(marketPart)) return outcomePart;
        return marketPart + "." + outcomePart;
    }

    private String findParentAttr(Element element) {
        Element current = element;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.hasAttr("data-market-type")) { String val = clean(current.attr("data-market-type")); if (val != null) return val; }
            current = current.parent();
        }
        return null;
    }

    private String extractMarketName(Element marketBlock) {
        String[] selectors = {".name-field", ".market-name", ".market-title", ".market-header", "[class*='market-name']", "[class*='market-title']"};
        for (String sel : selectors) {
            Element name = marketBlock.select(sel).first();
            if (name == null) continue;
            String val = clean(name.text());
            if (val != null && val.length() < 250) return val;
        }
        Elements headings = marketBlock.select("h1,h2,h3,h4,th");
        for (Element h : headings) {
            String val = clean(h.text());
            if (val != null && val.length() < 150 && parseOdds(val) == null) return val;
        }
        return null;
    }

    private List<RawEvent.RawOutcome> extractMarketOutcomes(Element marketBlock) {
        List<RawEvent.RawOutcome> outcomes = new ArrayList<>();
        for (Element table : marketBlock.select("table")) outcomes.addAll(extractTableOutcomes(table));
        if (outcomes.isEmpty()) outcomes.addAll(extractDivOutcomes(marketBlock));
        return deduplicateOutcomes(outcomes);
    }

    private List<RawEvent.RawOutcome> extractTableOutcomes(Element table) {
        List<RawEvent.RawOutcome> outcomes = new ArrayList<>();
        List<String> headers = extractHeaders(table);
        for (Element row : table.select("tr")) {
            String rowLabel = extractRowLabel(row);
            Elements priceElements = row.select("[data-selection-price]");
            if (priceElements.isEmpty()) priceElements = row.select(".price");
            for (Element pe : priceElements) {
                String priceText = clean(pe.attr("data-selection-price"));
                if (priceText == null) priceText = clean(pe.text());
                BigDecimal odds = parseOdds(priceText);
                if (odds == null) continue;
                String header = findHeaderForPrice(pe, headers);
                String outcomeName = buildOutcomeName(rowLabel, header);
                if (outcomeName == null) continue;
                outcomes.add(new RawEvent.RawOutcome(outcomeName, odds));
            }
        }
        return outcomes;
    }

    private List<String> extractHeaders(Element table) {
        List<String> headers = new ArrayList<>();
        Element headerRow = table.select("thead tr").first();
        if (headerRow == null) headerRow = table.select("tr").first();
        if (headerRow == null) return headers;
        for (Element cell : headerRow.select("th,td")) headers.add(clean(cell.text()));
        return headers;
    }

    private String extractRowLabel(Element row) {
        String[] selectors = {".selection-name", ".row-name", ".label", ".name-field", ".member-name"};
        for (String sel : selectors) {
            Element el = row.select(sel).first();
            if (el == null) continue;
            String text = clean(el.text());
            if (text != null && parseOdds(text) == null) return text;
        }
        for (Element cell : row.select("> th, > td")) {
            if (cell.hasClass("price") || cell.hasAttr("data-selection-price")) continue;
            String text = clean(cell.text());
            if (text != null && parseOdds(text) == null) return text;
        }
        return null;
    }

    private String findHeaderForPrice(Element priceElement, List<String> headers) {
        if (headers.isEmpty()) return null;
        Element cell = priceElement;
        if (!cell.tagName().equalsIgnoreCase("td") && !cell.tagName().equalsIgnoreCase("th")) {
            Element parent = priceElement.parent();
            if (parent != null && (parent.tagName().equalsIgnoreCase("td") || parent.tagName().equalsIgnoreCase("th"))) cell = parent;
        }
        int index = cell.elementSiblingIndex();
        if (index >= 0 && index < headers.size()) { String h = clean(headers.get(index)); if (h != null && !h.isBlank()) return h; }
        int shifted = index - 1;
        if (shifted >= 0 && shifted < headers.size()) return clean(headers.get(shifted));
        return null;
    }

    private List<RawEvent.RawOutcome> extractDivOutcomes(Element marketBlock) {
        List<RawEvent.RawOutcome> outcomes = new ArrayList<>();
        for (Element price : marketBlock.select("[data-selection-price]")) {
            String value = clean(price.attr("data-selection-price"));
            BigDecimal odds = parseOdds(value);
            if (odds == null) continue;
            String name = findOutcomeText(price);
            if (name == null) continue;
            outcomes.add(new RawEvent.RawOutcome(name, odds));
        }
        return outcomes;
    }

    private String findOutcomeText(Element price) {
        String value = clean(firstNonBlank(price.attr("data-selection-name"), price.attr("data-name"), price.attr("aria-label"), price.attr("title")));
        if (value != null && parseOdds(value) == null) return value;
        Element parent = price.parent();
        if (parent != null) {
            for (Element child : parent.children()) {
                if (child == price) continue;
                String text = clean(child.text());
                if (text != null && parseOdds(text) == null) return text;
            }
        }
        return null;
    }

    private String buildOutcomeName(String rowLabel, String header) {
        rowLabel = clean(rowLabel); header = clean(header);
        if (rowLabel == null && header == null) return null;
        if (rowLabel != null && header == null) return rowLabel;
        if (rowLabel == null) return header;
        if (rowLabel.equalsIgnoreCase(header)) return rowLabel;
        return rowLabel + " | " + header;
    }

    // ======================== UTILS ========================

    private BigDecimal parseOdds(String value) {
        if (value == null) return null;
        String normalized = value.trim().replace(",", ".");
        if (!NUMBER_PATTERN.matcher(normalized).matches()) return null;
        try {
            BigDecimal odds = new BigDecimal(normalized);
            if (odds.compareTo(BigDecimal.ZERO) <= 0 || odds.compareTo(new BigDecimal("10000")) > 0) return null;
            return odds;
        } catch (NumberFormatException e) { return null; }
    }

    private List<RawEvent.RawOutcome> deduplicateOutcomes(List<RawEvent.RawOutcome> outcomes) {
        Map<String, RawEvent.RawOutcome> unique = new LinkedHashMap<>();
        for (RawEvent.RawOutcome o : outcomes) {
            if (o == null || o.name() == null) continue;
            unique.putIfAbsent(o.name().trim().toLowerCase(Locale.ROOT), o);
        }
        return new ArrayList<>(unique.values());
    }

    private String buildFallbackEventId(Element eventBlock) {
        String[] teams = extractTeams(eventBlock);
        if (teams == null) return null;
        String time = clean(eventBlock.select(".date-wrapper").text());
        return "fallback_" + Integer.toHexString((teams[0] + "|" + teams[1] + "|" + time).hashCode());
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return null;
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        return result.isEmpty() ? null : result;
    }
}