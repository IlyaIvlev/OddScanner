package com.oddscanner.parser;

import lombok.Getter;

/**
 * Результат парсинга одного события.
 * Используется для передачи данных из парсера в сервис обработки.
 *
 * @param <T> тип распарсенного события (например, RawEvent)
 */
@Getter
public class ParseResult<T> {
    private final T data;
    private final String skipReason;
    private final boolean success;

    private ParseResult(T data, String skipReason, boolean success) {
        this.data = data;
        this.skipReason = skipReason;
        this.success = success;
    }

    public static <T> ParseResult<T> success(T data) {
        return new ParseResult<>(data, null, true);
    }

    public static <T> ParseResult<T> skip(String reason) {
        return new ParseResult<>(null, reason, false);
    }
}