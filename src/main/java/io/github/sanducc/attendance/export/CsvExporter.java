package io.github.sanducc.attendance.export;

import java.io.IOException;
import java.io.Writer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180-style CSV for Excel in a Romanian/EU locale: ';' separator (the EU list separator, so Excel splits the
 * columns on double-click), a UTF-8 BOM so the diacritics show correctly, CRLF line endings.
 */
public final class CsvExporter {

    public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";

    private static final char BOM = '﻿';
    private static final char SEPARATOR = ';';
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CsvExporter() {
    }

    public static void write(Writer out, AttendanceSheet sheet) throws IOException {
        out.write(BOM);
        writeLine(out, AttendanceSheet.HEADERS);
        for (AttendanceSheet.Row r : sheet.rows()) {
            List<String> cells = new ArrayList<>(AttendanceSheet.HEADERS.size());
            cells.add(Integer.toString(r.nr()));
            cells.add(r.lastName());
            cells.add(r.firstName());
            cells.add(r.email());
            cells.add(TIMESTAMP.format(r.checkedInAt()));
            cells.add(r.source());
            writeLine(out, cells);
        }
        out.flush();
    }

    /** The per-student stats (#8): same conventions; the percentage is written as e.g. "80%", dates as yyyy-MM-dd. */
    public static void write(Writer out, StatsSheet sheet) throws IOException {
        out.write(BOM);
        writeLine(out, StatsSheet.HEADERS);
        for (StatsSheet.Row r : sheet.rows()) {
            List<String> cells = new ArrayList<>(StatsSheet.HEADERS.size());
            cells.add(Integer.toString(r.nr()));
            cells.add(r.lastName());
            cells.add(r.firstName());
            cells.add(r.email());
            cells.add(Long.toString(r.attended()));
            cells.add(Long.toString(r.sessions()));
            cells.add(r.percent() + "%");
            cells.add(DateTimeFormatter.ISO_LOCAL_DATE.format(r.lastAttended()));
            cells.add(r.source());
            writeLine(out, cells);
        }
        out.flush();
    }

    private static void writeLine(Writer out, List<String> values) throws IOException {
        StringBuilder line = new StringBuilder();
        for (String value : values) {
            if (!line.isEmpty()) {
                line.append(SEPARATOR);
            }
            line.append(cell(value));
        }
        out.write(line.append("\r\n").toString());
    }

    static String cell(String value) {
        if (value == null) {
            return "";
        }
        // Names are user-provided: neutralise spreadsheet formula injection.
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean quote = safe.indexOf(SEPARATOR) >= 0 || safe.contains("\"") || safe.contains("\n")
                || safe.contains("\r") || !safe.equals(value);
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
