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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class MarathonParser extends AbstractBookmakerParser {

    private static final String BASE_URL =
            "https://www.marathonbet.ru";

    /**
     * Одна основная футбольная страница.
     * Не ходим отдельно в каждый матч.
     * На этой странице уже находятся события и рынки.
     */
    private static final String FOOTBALL_URL =
            BASE_URL + "/su/popular/Football+-+11";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern TIME_PATTERN =
            Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b");

    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("^\\d+(?:[.,]\\d+)?$");

    private final EventRepository eventRepository;

    public MarathonParser(
            MeterRegistry meterRegistry,
            EventRepository eventRepository
    ) {
        super(meterRegistry, eventRepository);
        this.eventRepository = eventRepository;
    }

    @Override
    public String getName() {
        return "Marathon";
    }

    @Override
    public List<RawEvent> doParse() throws Exception {

        log.info("[Marathon] Запуск парсера");

        String html = loadPage();

        if (html == null || html.isBlank()) {
            log.warn("[Marathon] Получен пустой HTML");
            return Collections.emptyList();
        }

        log.info(
                "[Marathon] HTML получен: {} KB",
                html.length() / 1024
        );

        List<RawEvent> events = parseHtml(html);

        if (events.isEmpty()) {
            log.warn("[Marathon] События не найдены");
            return events;
        }

        eventRepository.saveEvents(
                "MARATHON",
                events
        );

        Set<String> activeExternalIds =
                events.stream()
                        .map(RawEvent::externalId)
                        .collect(java.util.stream.Collectors.toSet());

        eventRepository.markInactiveEvents(
                "MARATHON",
                activeExternalIds
        );

        log.info(
                "[Marathon] СОХРАНЕНО {} событий",
                events.size()
        );

        return events;
    }

    /**
     * Загружаем только основную страницу.
     */
    private String loadPage() {

        try (Playwright playwright = Playwright.create()) {

            try (Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(List.of(
                                    "--disable-blink-features=AutomationControlled",
                                    "--no-sandbox",
                                    "--disable-dev-shm-usage"
                            ))
            ); BrowserContext context =
                         browser.newContext(
                                 new Browser.NewContextOptions()
                                         .setUserAgent(
                                                 "Mozilla/5.0 " +
                                                         "(Windows NT 10.0; Win64; x64) " +
                                                         "AppleWebKit/537.36 " +
                                                         "(KHTML, like Gecko) " +
                                                         "Chrome/125.0.0.0 Safari/537.36"
                                         )
                                         .setLocale("ru-RU")
                                         .setTimezoneId("Europe/Moscow")
                                         .setViewportSize(1920, 1080)
                                         .setExtraHTTPHeaders(
                                                 Map.of(
                                                         "Accept-Language",
                                                         "ru-RU,ru;q=0.9,en;q=0.8"
                                                 )
                                         )
                         )) {

                Page page = context.newPage();

                page.addInitScript("""
                        Object.defineProperty(
                            navigator,
                            'webdriver',
                            { get: () => undefined }
                        );
                        
                        Object.defineProperty(
                            navigator,
                            'languages',
                            { get: () => ['ru-RU', 'ru', 'en'] }
                        );
                        
                        window.chrome = {
                            runtime: {}
                        };
                        """);

                log.info(
                        "[Marathon] Открываем {}",
                        FOOTBALL_URL
                );

                page.navigate(
                        FOOTBALL_URL,
                        new Page.NavigateOptions()
                                .setTimeout(30_000)
                                .setWaitUntil(
                                        WaitUntilState.DOMCONTENTLOADED
                                )
                );

                log.info(
                        "[Marathon] Страница загружена. URL={}",
                        page.url()
                );

                /*
                 * Ждём появления динамического DOM.
                 */
                page.waitForTimeout(5000);

                /*
                 * Прокручиваем страницу несколько раз.
                 *
                 * Не открываем страницы отдельных матчей.
                 */
                for (int i = 0; i < 12; i++) {

                    page.mouse().wheel(0, 1800);

                    page.waitForTimeout(300);
                }

                page.waitForTimeout(1000);

                String html = page.content();

                log.info(
                        "[Marathon] Получен DOM страницы: {} KB",
                        html.length() / 1024
                );

                page.close();

                return html;

            }

        } catch (Exception e) {

            log.error(
                    "[Marathon] Ошибка загрузки страницы",
                    e
            );

            return null;
        }
    }

    /**
     * Разбираем основную страницу.
     */
    private List<RawEvent> parseHtml(String html) {

        List<RawEvent> result =
                new ArrayList<>();

        Document document =
                Jsoup.parse(html);

        /*
         * Основной селектор.
         */
        Elements eventBlocks =
                document.select(
                        "[data-event-eventid]"
                );

        log.info(
                "[Marathon] Найдено элементов с data-event-eventid: {}",
                eventBlocks.size()
        );

        /*
         * Если вдруг основной селектор изменился,
         * пробуем более общий вариант.
         */
        if (eventBlocks.isEmpty()) {

            eventBlocks =
                    document.select(
                            ".coupon-row"
                    );

            log.info(
                    "[Marathon] Резервный поиск coupon-row: {}",
                    eventBlocks.size()
            );
        }

        Set<String> processedEventIds =
                new HashSet<>();

        int skipped = 0;

        for (Element eventBlock : eventBlocks) {

            try {

                String eventId =
                        firstNonBlank(
                                eventBlock.attr("data-event-eventid"),
                                eventBlock.attr("data-event-id"),
                                eventBlock.attr("data-id")
                        );

                eventId = clean(eventId);

                /*
                 * Если ID нет — всё равно пытаемся
                 * разобрать блок.
                 */
                if (eventId == null) {

                    eventId =
                            buildFallbackEventId(
                                    eventBlock
                            );
                }

                if (eventId == null) {
                    skipped++;
                    continue;
                }

                if (!processedEventIds.add(eventId)) {
                    continue;
                }

                /*
                 * Live пока не берём.
                 */
                String live =
                        firstNonBlank(
                                eventBlock.attr("data-live"),
                                eventBlock.attr("data-event-live")
                        );

                if ("true".equalsIgnoreCase(
                        clean(live)
                )) {
                    continue;
                }

                RawEvent event =
                        parseEvent(
                                eventBlock,
                                eventId
                        );

                if (event == null) {
                    skipped++;
                    continue;
                }

                result.add(event);

                log.info(
                        "[Marathon] EVENT: {} - {} | {} | рынков={}",
                        event.team1(),
                        event.team2(),
                        event.startsAt(),
                        event.markets().size()
                );

            } catch (Exception e) {

                skipped++;

                log.warn(
                        "[Marathon] Ошибка обработки блока: {}",
                        e.getMessage()
                );
            }
        }

        log.info(
                "[Marathon] Всего распарсено событий: {}",
                result.size()
        );

        log.info(
                "[Marathon] Пропущено блоков: {}",
                skipped
        );

        return result;
    }

    /**
     * Разбираем одно событие.
     */
    private RawEvent parseEvent(
            Element eventBlock,
            String eventId
    ) {

        /*
         * Сначала ищем команды.
         */
        String[] teams =
                extractTeams(eventBlock);

        if (teams == null) {

            log.debug(
                    "[Marathon] Не удалось определить команды. id={}",
                    eventId
            );

            return null;
        }

        String team1 = teams[0];
        String team2 = teams[1];

        /*
         * Время.
         */
        LocalDateTime startsAt =
                extractStartTime(eventBlock);

        /*
         * Лига.
         */
        String league =
                extractLeague(eventBlock);

        /*
         * Все рынки непосредственно из блока.
         */
        List<RawEvent.RawMarket> markets =
                extractMarkets(eventBlock);

        if (markets.isEmpty()) {

            log.debug(
                    "[Marathon] {} - {}: рынков не найдено. id={}",
                    team1,
                    team2,
                    eventId
            );

            return null;
        }

        String eventUrl =
                extractEventUrl(eventBlock);

        String externalId =
                "marathon_" + eventId;

        return new RawEvent(
                externalId,
                "Футбол",
                league,
                team1,
                team2,
                startsAt,
                markets,
                eventUrl == null
                        ? ""
                        : eventUrl
        );
    }

    /**
     * Извлечение команд.
     * Здесь специально несколько вариантов,
     * потому что структура Marathon может отличаться
     * между блоками.
     */
    private String[] extractTeams(
            Element eventBlock
    ) {

        /*
         * Вариант 1.
         */
        Elements members =
                eventBlock.select(
                        ".member-name .member-link"
                );

        String[] result =
                extractFirstTwoTexts(members);

        if (result != null) {
            return result;
        }

        /*
         * Вариант 2.
         */
        members =
                eventBlock.select(
                        ".member-name"
                );

        result =
                extractFirstTwoTexts(members);

        if (result != null) {
            return result;
        }

        /*
         * Вариант 3.
         */
        members =
                eventBlock.select(
                        "[data-member-name]"
                );

        result =
                extractFirstTwoAttributes(
                        members
                );

        if (result != null) {
            return result;
        }

        /*
         * Вариант 4.
         */
        members =
                eventBlock.select(
                        ".member-link"
                );

        result =
                extractFirstTwoTexts(members);

        if (result != null) {
            return result;
        }

        /*
         * Вариант 5 — data-event-name.
         */
        String eventName =
                firstNonBlank(
                        eventBlock.attr("data-event-name"),
                        eventBlock.attr("data-event-title"),
                        eventBlock.attr("data-name")
                );

        eventName = clean(eventName);

        result =
                splitTeams(eventName);

        if (result != null) {
            return result;
        }

        /*
         * Вариант 6 — берём текст блока и
         * ищем конструкцию:
         *
         * Команда 1 - Команда 2
         */
        String text =
                clean(eventBlock.text());

        return splitTeams(text);
    }

    private String[] extractFirstTwoTexts(
            Elements elements
    ) {

        List<String> values =
                new ArrayList<>();

        for (Element element : elements) {

            String value =
                    clean(element.text());

            if (value == null) {
                continue;
            }

            /*
             * Не считаем числовые элементы командами.
             */
            if (parseOdds(value) != null) {
                continue;
            }

            if (!values.contains(value)) {
                values.add(value);
            }

            if (values.size() == 2) {
                break;
            }
        }

        if (values.size() < 2) {
            return null;
        }

        return new String[]{
                values.get(0),
                values.get(1)
        };
    }

    private String[] extractFirstTwoAttributes(
            Elements elements
    ) {

        List<String> values =
                new ArrayList<>();

        for (Element element : elements) {

            String value =
                    clean(element.attr("data-member-name"));

            if (value == null) {
                continue;
            }

            if (!values.contains(value)) {
                values.add(value);
            }

            if (values.size() == 2) {
                break;
            }
        }

        if (values.size() < 2) {
            return null;
        }

        return new String[]{
                values.get(0),
                values.get(1)
        };
    }

    /**
     * Разделяем название события на команды.
     */
    private String[] splitTeams(
            String text
    ) {

        if (text == null) {
            return null;
        }

        String[] separators = {
                " - ",
                " — ",
                " – ",
                " vs ",
                " VS ",
                " против "
        };

        for (String separator : separators) {

            int index =
                    text.indexOf(separator);

            if (index <= 0) {
                continue;
            }

            String team1 =
                    clean(
                            text.substring(
                                    0,
                                    index
                            )
                    );

            String team2 =
                    clean(
                            text.substring(
                                    index + separator.length()
                            )
                    );

            if (team1 == null ||
                    team2 == null) {
                continue;
            }

            /*
             * Отсекаем слишком длинные варианты,
             * которые явно являются не названием матча.
             */
            if (team1.length() > 150 ||
                    team2.length() > 150) {
                continue;
            }

            return new String[]{
                    team1,
                    team2
            };
        }

        return null;
    }

    /**
     * URL события.
     */
    private String extractEventUrl(
            Element eventBlock
    ) {

        Element link =
                eventBlock.select(
                                "a[href]"
                        ).stream()
                        .filter(
                                e -> {
                                    String href =
                                            clean(e.attr("href"));

                                    return href != null &&
                                            (
                                                    href.contains("/betting/") ||
                                                            href.contains("/su/")
                                            );
                                }
                        )
                        .findFirst()
                        .orElse(null);

        if (link == null) {
            return null;
        }

        String href =
                clean(link.attr("href"));

        if (href == null) {
            return null;
        }

        if (href.startsWith("http://") ||
                href.startsWith("https://")) {
            return href;
        }

        if (href.startsWith("/")) {
            return BASE_URL + href;
        }

        return BASE_URL + "/" + href;
    }

    /**
     * Время события.
     */
    private LocalDateTime extractStartTime(
            Element eventBlock
    ) {

        String dateText =
                firstNonBlank(
                        eventBlock.select(".date-wrapper").text(),
                        eventBlock.select(".date").text(),
                        eventBlock.select(
                                "[class*='date']"
                        ).text()
                );

        dateText = clean(dateText);

        LocalDate today =
                LocalDate.now();

        if (dateText == null) {
            return today.atStartOfDay();
        }

        Matcher matcher =
                TIME_PATTERN.matcher(dateText);

        if (!matcher.find()) {
            return today.atStartOfDay();
        }

        try {

            LocalTime time =
                    LocalTime.parse(
                            matcher.group(1),
                            TIME_FORMAT
                    );

            LocalDate date =
                    today;

            String lower =
                    dateText.toLowerCase(
                            Locale.ROOT
                    );

            if (lower.contains("завтра")) {
                date = today.plusDays(1);
            }

            return LocalDateTime.of(
                    date,
                    time
            );

        } catch (DateTimeParseException e) {

            return today.atStartOfDay();
        }
    }

    /**
     * Лига.
     */
    private String extractLeague(
            Element eventBlock
    ) {

        String[] selectors = {
                ".sport-category-name",
                ".category-name",
                ".name-field",
                ".category-link",
                "[class*='category']"
        };

        for (String selector : selectors) {

            Element element =
                    eventBlock
                            .select(selector)
                            .first();

            if (element == null) {
                continue;
            }

            String value =
                    clean(element.text());

            if (value != null &&
                    value.length() < 200) {

                return value;
            }
        }

        String path =
                firstNonBlank(
                        eventBlock.attr("data-event-path"),
                        eventBlock.attr("data-path")
                );

        path = clean(path);

        if (path != null) {

            String decoded =
                    path
                            .replace("+", " ")
                            .replace("%20", " ");

            String[] parts =
                    decoded.split("/");

            if (parts.length >= 3) {

                String league =
                        clean(parts[2]);

                if (league != null) {
                    return league;
                }
            }
        }

        return "Футбол";
    }

    /**
     * =========================================================
     *                     РЫНКИ
     * =========================================================
     * Здесь намеренно НЕ ограничиваемся одним конкретным
     * классом Marathon.
     * Ищем все потенциальные market-контейнеры.
     */
    private List<RawEvent.RawMarket> extractMarkets(
            Element eventBlock
    ) {

        List<RawEvent.RawMarket> markets =
                new ArrayList<>();

        Set<String> processed =
                new HashSet<>();

        /*
         * Основной вариант.
         */
        Elements marketBlocks =
                eventBlock.select(
                        "[data-preference-id]"
                );

        log.debug(
                "[Marathon] Потенциальных market blocks: {}",
                marketBlocks.size()
        );

        /*
         * Если preference-id отсутствует,
         * ищем по классам.
         */
        if (marketBlocks.isEmpty()) {

            marketBlocks =
                    eventBlock.select(
                            "[class*='market']"
                    );
        }

        for (Element marketBlock :
                marketBlocks) {

            try {

                /*
                 * Не считаем вложенные элементы отдельными
                 * рынками, если они сами являются частью
                 * market-контейнера.
                 */
                Element parentMarket =
                        marketBlock.parent();

                if (parentMarket != null &&
                        parentMarket != eventBlock &&
                        parentMarket.hasAttr(
                                "data-preference-id"
                        )) {
                    continue;
                }

                String preferenceId =
                        clean(
                                marketBlock.attr(
                                        "data-preference-id"
                                )
                        );

                String marketName =
                        extractMarketName(
                                marketBlock
                        );

                /*
                 * Даже если название рынка не найдено,
                 * попробуем построить его из preference-id.
                 */
                if (marketName == null) {

                    marketName =
                            preferenceId == null
                                    ? "Рынок"
                                    : "Рынок " + preferenceId;
                }

                List<RawEvent.RawOutcome> outcomes =
                        extractMarketOutcomes(
                                marketBlock
                        );

                if (outcomes.isEmpty()) {
                    continue;
                }

                String key =
                        marketName
                                .trim()
                                .toLowerCase(
                                        Locale.ROOT
                                );

                if (!processed.add(key)) {
                    continue;
                }

                markets.add(
                        new RawEvent.RawMarket(
                                marketName,
                                outcomes
                        )
                );

            } catch (Exception e) {

                log.debug(
                        "[Marathon] Ошибка разбора market: {}",
                        e.getMessage()
                );
            }
        }

        /*
         * Иногда рынки представлены таблицами
         * непосредственно внутри eventBlock,
         * без ожидаемого market-wrapper.
         */
        if (markets.isEmpty()) {

            log.debug(
                    "[Marathon] Wrapper markets не найдены. " +
                            "Пробуем искать таблицы напрямую."
            );

            Elements tables =
                    eventBlock.select("table");

            int counter = 0;

            for (Element table : tables) {

                List<RawEvent.RawOutcome> outcomes =
                        extractTableOutcomes(table);

                if (outcomes.isEmpty()) {
                    continue;
                }

                counter++;

                markets.add(
                        new RawEvent.RawMarket(
                                "Рынок " + counter,
                                outcomes
                        )
                );
            }
        }

        log.debug(
                "[Marathon] Найдено рынков: {}",
                markets.size()
        );

        return markets;
    }

    /**
     * Название рынка.
     */
    private String extractMarketName(
            Element marketBlock
    ) {

        String[] selectors = {
                ".name-field",
                ".market-name",
                ".market-title",
                ".market-header",
                "[class*='market-name']",
                "[class*='market-title']"
        };

        for (String selector : selectors) {

            Element name =
                    marketBlock
                            .select(selector)
                            .first();

            if (name == null) {
                continue;
            }

            String value =
                    clean(name.text());

            if (value != null &&
                    value.length() < 250) {

                return value;
            }
        }

        /*
         * Если preference-id есть, а name-field нет,
         * можно посмотреть ближайший заголовок.
         */
        Elements headings =
                marketBlock.select(
                        "h1,h2,h3,h4,th"
                );

        for (Element heading : headings) {

            String value =
                    clean(heading.text());

            if (value != null &&
                    value.length() < 150 &&
                    parseOdds(value) == null) {

                return value;
            }
        }

        return null;
    }

    /**
     * Извлекаем ВСЕ исходы рынка.
     */
    private List<RawEvent.RawOutcome> extractMarketOutcomes(
            Element marketBlock
    ) {

        List<RawEvent.RawOutcome> outcomes =
                new ArrayList<>();

        /*
         * Сначала работаем с таблицами.
         */
        Elements tables =
                marketBlock.select("table");

        for (Element table : tables) {

            outcomes.addAll(
                    extractTableOutcomes(table)
            );
        }

        /*
         * Иногда Marathon использует div вместо table.
         */
        if (outcomes.isEmpty()) {

            outcomes.addAll(
                    extractDivOutcomes(
                            marketBlock
                    )
            );
        }

        return deduplicateOutcomes(
                outcomes
        );
    }

    /**
     * Парсим таблицу.
     */
    private List<RawEvent.RawOutcome> extractTableOutcomes(
            Element table
    ) {

        List<RawEvent.RawOutcome> outcomes =
                new ArrayList<>();

        List<String> headers =
                extractHeaders(table);

        Elements rows =
                table.select("tr");

        for (Element row : rows) {

            String rowLabel =
                    extractRowLabel(row);

            /*
             * Ищем ВСЕ элементы с коэффициентами,
             * а не только td.price.
             */
            Elements priceElements =
                    row.select(
                            "[data-selection-price]"
                    );

            /*
             * Резервный вариант.
             */
            if (priceElements.isEmpty()) {

                priceElements =
                        row.select(
                                ".price"
                        );
            }

            for (Element priceElement :
                    priceElements) {

                String priceText =
                        clean(
                                priceElement.attr(
                                        "data-selection-price"
                                )
                        );

                if (priceText == null) {
                    priceText =
                            clean(
                                    priceElement.text()
                            );
                }

                BigDecimal odds =
                        parseOdds(priceText);

                if (odds == null) {
                    continue;
                }

                /*
                 * Определяем header через ближайшую
                 * позицию ячейки.
                 */
                String header =
                        findHeaderForPrice(
                                priceElement,
                                headers
                        );

                String outcomeName =
                        buildOutcomeName(
                                rowLabel,
                                header
                        );

                if (outcomeName == null) {
                    continue;
                }

                outcomes.add(
                        new RawEvent.RawOutcome(
                                outcomeName,
                                odds
                        )
                );
            }
        }

        return outcomes;
    }

    /**
     * Ищем заголовки.
     */
    private List<String> extractHeaders(
            Element table
    ) {

        List<String> headers =
                new ArrayList<>();

        Element headerRow =
                table.select("thead tr").first();

        if (headerRow == null) {
            headerRow =
                    table.select("tr").first();
        }

        if (headerRow == null) {
            return headers;
        }

        Elements cells =
                headerRow.select("th,td");

        for (Element cell : cells) {

            String text =
                    clean(cell.text());

            headers.add(text);
        }

        return headers;
    }

    /**
     * Label строки.
     */
    private String extractRowLabel(
            Element row
    ) {

        /*
         * Сначала специальные label-классы.
         */
        String[] selectors = {
                ".selection-name",
                ".row-name",
                ".label",
                ".name-field",
                ".member-name"
        };

        for (String selector : selectors) {

            Element element =
                    row.select(selector).first();

            if (element == null) {
                continue;
            }

            String text =
                    clean(element.text());

            if (text != null &&
                    parseOdds(text) == null) {

                return text;
            }
        }

        /*
         * Затем обычные td/th, исключая коэффициенты.
         */
        for (Element cell :
                row.select("> th, > td")) {

            if (cell.hasClass("price")) {
                continue;
            }

            if (cell.hasAttr(
                    "data-selection-price"
            )) {
                continue;
            }

            String text =
                    clean(cell.text());

            if (text == null) {
                continue;
            }

            if (parseOdds(text) != null) {
                continue;
            }

            return text;
        }

        return null;
    }

    /**
     * Связываем коэффициент с заголовком.
     */
    private String findHeaderForPrice(
            Element priceElement,
            List<String> headers
    ) {

        if (headers.isEmpty()) {
            return null;
        }

        Element cell =
                priceElement;

        if (!cell.tagName().equalsIgnoreCase("td") &&
                !cell.tagName().equalsIgnoreCase("th")) {

            Element parent =
                    priceElement.parent();

            if (parent != null &&
                    (
                            parent.tagName().equalsIgnoreCase("td") ||
                                    parent.tagName().equalsIgnoreCase("th")
                    )
            ) {
                cell = parent;
            }
        }

        int index =
                cell.elementSiblingIndex();

        /*
         * Часто первая колонка — label,
         * поэтому header может быть смещён.
         */
        if (index >= 0 &&
                index < headers.size()) {

            String header =
                    clean(headers.get(index));

            if (header != null &&
                    !header.isBlank()) {

                return header;
            }
        }

        /*
         * Если смещение на один.
         */
        int shifted =
                index - 1;

        if (shifted >= 0 &&
                shifted < headers.size()) {

            return clean(
                    headers.get(shifted)
            );
        }

        return null;
    }

    /**
     * Резервный parser div-исходов.
     */
    private List<RawEvent.RawOutcome> extractDivOutcomes(
            Element marketBlock
    ) {

        List<RawEvent.RawOutcome> outcomes =
                new ArrayList<>();

        Elements prices =
                marketBlock.select(
                        "[data-selection-price]"
                );

        for (Element price :
                prices) {

            String value =
                    clean(
                            price.attr(
                                    "data-selection-price"
                            )
                    );

            BigDecimal odds =
                    parseOdds(value);

            if (odds == null) {
                continue;
            }

            String name =
                    findOutcomeText(price);

            if (name == null) {
                continue;
            }

            outcomes.add(
                    new RawEvent.RawOutcome(
                            name,
                            odds
                    )
            );
        }

        return outcomes;
    }

    /**
     * Пытаемся найти название исхода около коэффициента.
     */
    private String findOutcomeText(
            Element price
    ) {

        /*
         * data-selection-name.
         */
        String value =
                firstNonBlank(
                        price.attr(
                                "data-selection-name"
                        ),
                        price.attr(
                                "data-name"
                        ),
                        price.attr(
                                "aria-label"
                        ),
                        price.attr(
                                "title"
                        )
                );

        value = clean(value);

        if (value != null &&
                parseOdds(value) == null) {

            return value;
        }

        /*
         * Соседний элемент.
         */
        Element parent =
                price.parent();

        if (parent != null) {

            for (Element child :
                    parent.children()) {

                if (child == price) {
                    continue;
                }

                String text =
                        clean(child.text());

                if (text == null) {
                    continue;
                }

                if (parseOdds(text) != null) {
                    continue;
                }

                return text;
            }
        }

        return null;
    }

    /**
     * Строим название исхода.
     */
    private String buildOutcomeName(
            String rowLabel,
            String header
    ) {

        rowLabel = clean(rowLabel);
        header = clean(header);

        if (rowLabel == null &&
                header == null) {
            return null;
        }

        if (rowLabel != null &&
                header == null) {
            return rowLabel;
        }

        if (rowLabel == null) {
            return header;
        }

        if (rowLabel.equalsIgnoreCase(header)) {
            return rowLabel;
        }

        return rowLabel + " | " + header;
    }

    /**
     * Парсим коэффициент.
     */
    private BigDecimal parseOdds(
            String value
    ) {

        if (value == null) {
            return null;
        }

        String normalized =
                value
                        .trim()
                        .replace(",", ".");

        if (!NUMBER_PATTERN.matcher(
                normalized
        ).matches()) {
            return null;
        }

        try {

            BigDecimal odds =
                    new BigDecimal(normalized);

            if (odds.compareTo(
                    BigDecimal.ZERO
            ) <= 0) {
                return null;
            }

            if (odds.compareTo(
                    new BigDecimal("10000")
            ) > 0) {
                return null;
            }

            return odds;

        } catch (NumberFormatException e) {

            return null;
        }
    }

    /**
     * Удаляем одинаковые исходы.
     */
    private List<RawEvent.RawOutcome> deduplicateOutcomes(
            List<RawEvent.RawOutcome> outcomes
    ) {

        Map<String, RawEvent.RawOutcome> unique =
                new LinkedHashMap<>();

        for (RawEvent.RawOutcome outcome :
                outcomes) {

            if (outcome == null ||
                    outcome.name() == null) {
                continue;
            }

            String key =
                    outcome.name()
                            .trim()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            /*
             * Если одинаковый исход встретился
             * несколько раз, оставляем первый.
             */
            unique.putIfAbsent(
                    key,
                    outcome
            );
        }

        return new ArrayList<>(
                unique.values()
        );
    }

    /**
     * Если у блока нет ID,
     * создаём стабильный fallback.
     */
    private String buildFallbackEventId(
            Element eventBlock
    ) {

        String[] teams =
                extractTeams(eventBlock);

        if (teams == null) {
            return null;
        }

        String time =
                clean(
                        eventBlock
                                .select(".date-wrapper")
                                .text()
                );

        String raw =
                teams[0] +
                        "|" +
                        teams[1] +
                        "|" +
                        time;

        return "fallback_" +
                Integer.toHexString(
                        raw.hashCode()
                );
    }

    private String firstNonBlank(
            String... values
    ) {

        if (values == null) {
            return null;
        }

        for (String value : values) {

            if (value == null) {
                continue;
            }

            if (!value.trim().isEmpty()) {
                return value;
            }
        }

        return null;
    }

    /**
     * Чистим HTML-текст.
     */
    private String clean(
            String value
    ) {

        if (value == null) {
            return null;
        }

        String result =
                value
                        .replace('\u00A0', ' ')
                        .replaceAll("\\s+", " ")
                        .trim();

        if (result.isEmpty()) {
            return null;
        }

        return result;
    }
}