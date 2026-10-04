package com.oddscanner.parser;

import java.util.List;

public interface BookmakerParser {
    String getName();
    List<RawEvent> doParse() throws Exception;
}