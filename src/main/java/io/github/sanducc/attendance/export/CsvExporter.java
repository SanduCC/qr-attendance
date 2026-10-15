package io.github.sanducc.attendance.export;

import io.github.sanducc.attendance.checkin.RecordView;
import java.io.IOException;
import java.io.Writer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** RFC 4180 CSV with a UTF-8 BOM so Excel shows Romanian diacritics correctly. */
public final class CsvExporter {

    private static final char BOM = '﻿';
    private static final String HEADER = "last_name,first_name,email,checked_in_at";

    private CsvExporter() {
    }

    public static void write(Writer out, List<RecordView> records, ZoneId zone) throws IOException {
        DateTimeFormatter timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone);
        out.write(BOM);
        out.write(HEADER);
        out.write("\r\n");
        for (RecordView r : records) {
            out.write(String.join(",",
                    cell(r.lastName()), cell(r.firstName()), cell(r.email()),
                    cell(timestamp.format(r.checkedInAt()))));
            out.write("\r\n");
        }
        out.flush();
    }

    static String cell(String value) {
        if (value == null) {
            return "";
        }
        // Names are user-provided: neutralise spreadsheet formula injection.
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean quote = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
                || !safe.equals(value);
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
