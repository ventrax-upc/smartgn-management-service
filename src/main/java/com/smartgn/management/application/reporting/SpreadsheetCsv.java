package com.smartgn.management.application.reporting;

import java.util.List;
import java.util.stream.Collectors;

/** RFC 4180 quoting with a fixed text marker for untrusted spreadsheet cells. */
public final class SpreadsheetCsv {
    private SpreadsheetCsv() {}

    public static String row(List<?> cells) {
        return cells.stream().map(SpreadsheetCsv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    public static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        // Do not allow separators, control characters or formula prefixes to become new cells.
        // The fixed printable marker remains after a spreadsheet save/reopen round trip.
        int first = 0;
        while (first < text.length() && (Character.isWhitespace(text.charAt(first))
                || Character.isSpaceChar(text.charAt(first)) || text.charAt(first) == '\uFEFF')) first++;
        String probe = text.substring(first);
        if (text.chars().anyMatch(c -> c < 32) || (!probe.isEmpty()
                && "=+-@＝＋－＠".indexOf(probe.charAt(0)) >= 0)) {
            text = "[text] " + text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
