package io.github.sanducc.attendance.export;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Function;
import org.dhatim.fastexcel.BorderSide;
import org.dhatim.fastexcel.BorderStyle;
import org.dhatim.fastexcel.PaperSize;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * A real .xlsx (one sheet, "Attendance"): title block, then a bold, frozen, filterable header and one row per
 * check-in. Names and emails are written as plain string cells, which Excel never evaluates as formulas; the
 * check-in time is a real date/time cell shown as HH:mm:ss. The last column says "scan" or (bold) "manual".
 *
 * <p>The per-student stats of a course (#8) use the same layout on a "Stats" sheet: the counts are numbers, the
 * percentage a real percentage cell, the last attended date a real date cell.
 */
public final class XlsxExporter {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String SHEET_NAME = "Attendance";
    public static final String STATS_SHEET_NAME = "Stats";

    static final int TITLE_ROW = 0;
    static final int SUBTITLE_ROW = 1;
    static final int HEADER_ROW = 3;
    static final String TIME_FORMAT = "HH:mm:ss";
    static final String PERCENT_FORMAT = "0%";
    static final String DATE_FORMAT = "yyyy-mm-dd";

    private static final int COL_NR = 0;
    private static final int COL_LAST = 1;
    private static final int COL_FIRST = 2;
    private static final int COL_EMAIL = 3;
    private static final int COL_TIME = 4;
    private static final int COL_SOURCE = 5;

    private static final int COL_ATTENDED = 4;
    private static final int COL_SESSIONS = 5;
    private static final int COL_PERCENT = 6;
    private static final int COL_LAST_ATTENDED = 7;
    private static final int COL_STATS_SOURCE = 8;

    private static final int MIN_WIDTH = 6;
    private static final int MAX_WIDTH = 50;

    private XlsxExporter() {
    }

    public static void write(OutputStream out, AttendanceSheet sheet) throws IOException {
        Workbook workbook = new Workbook(out, "qr-attendance", "1.0");
        Worksheet ws = workbook.newWorksheet(SHEET_NAME);
        List<String> headers = AttendanceSheet.HEADERS;
        titleAndHeader(ws, sheet.title(), sheet.subtitle(), headers);

        int row = HEADER_ROW;
        for (AttendanceSheet.Row r : sheet.rows()) {
            row++;
            ws.value(row, COL_NR, r.nr());
            ws.value(row, COL_LAST, r.lastName());
            ws.value(row, COL_FIRST, r.firstName());
            if (r.email() != null) {
                ws.value(row, COL_EMAIL, r.email());
            }
            ws.value(row, COL_TIME, r.checkedInAt());
            ws.style(row, COL_TIME).format(TIME_FORMAT).horizontalAlignment("right").set();
            ws.value(row, COL_SOURCE, r.source());
            if (r.manual()) {
                ws.style(row, COL_SOURCE).bold().set();
            }
        }
        pageSetup(ws, row, headers.size());

        List<AttendanceSheet.Row> rows = sheet.rows();
        ws.width(COL_NR, width(headers.get(COL_NR), column(rows, r -> Integer.toString(r.nr()))));
        ws.width(COL_LAST, width(headers.get(COL_LAST), column(rows, AttendanceSheet.Row::lastName)));
        ws.width(COL_FIRST, width(headers.get(COL_FIRST), column(rows, AttendanceSheet.Row::firstName)));
        ws.width(COL_EMAIL, width(headers.get(COL_EMAIL), column(rows, AttendanceSheet.Row::email)));
        ws.width(COL_TIME, width(headers.get(COL_TIME), List.of(TIME_FORMAT)));
        ws.width(COL_SOURCE, width(headers.get(COL_SOURCE), List.of(AttendanceSheet.SOURCE_MANUAL)));

        workbook.finish();
    }

    /** The per-student stats of a course (#8); a typed name's "manual" source is bold, like a manual entry. */
    public static void write(OutputStream out, StatsSheet sheet) throws IOException {
        Workbook workbook = new Workbook(out, "qr-attendance", "1.0");
        Worksheet ws = workbook.newWorksheet(STATS_SHEET_NAME);
        List<String> headers = StatsSheet.HEADERS;
        titleAndHeader(ws, sheet.title(), sheet.subtitle(), headers);

        int row = HEADER_ROW;
        for (StatsSheet.Row r : sheet.rows()) {
            row++;
            ws.value(row, COL_NR, r.nr());
            ws.value(row, COL_LAST, r.lastName());
            ws.value(row, COL_FIRST, r.firstName());
            if (r.email() != null) {
                ws.value(row, COL_EMAIL, r.email());
            }
            ws.value(row, COL_ATTENDED, r.attended());
            ws.value(row, COL_SESSIONS, r.sessions());
            ws.value(row, COL_PERCENT, r.percent() / 100.0);
            ws.style(row, COL_PERCENT).format(PERCENT_FORMAT).set();
            ws.value(row, COL_LAST_ATTENDED, r.lastAttended());
            ws.style(row, COL_LAST_ATTENDED).format(DATE_FORMAT).horizontalAlignment("right").set();
            ws.value(row, COL_STATS_SOURCE, r.source());
            if (r.typedName()) {
                ws.style(row, COL_STATS_SOURCE).bold().set();
            }
        }
        pageSetup(ws, row, headers.size());
        // Nine columns: landscape keeps them on one A4 page width.
        ws.pageOrientation("landscape");

        List<StatsSheet.Row> rows = sheet.rows();
        ws.width(COL_NR, width(headers.get(COL_NR), column(rows, r -> Integer.toString(r.nr()))));
        ws.width(COL_LAST, width(headers.get(COL_LAST), column(rows, StatsSheet.Row::lastName)));
        ws.width(COL_FIRST, width(headers.get(COL_FIRST), column(rows, StatsSheet.Row::firstName)));
        ws.width(COL_EMAIL, width(headers.get(COL_EMAIL), column(rows, StatsSheet.Row::email)));
        for (int col = COL_ATTENDED; col <= COL_LAST_ATTENDED; col++) {
            ws.width(col, width(headers.get(col), List.of(DATE_FORMAT)));
        }
        ws.width(COL_STATS_SOURCE, width(headers.get(COL_STATS_SOURCE), List.of(StatsSheet.SOURCE_ACCOUNT)));

        workbook.finish();
    }

    private static void titleAndHeader(Worksheet ws, String title, String subtitle, List<String> headers) {
        ws.value(TITLE_ROW, 0, title);
        ws.style(TITLE_ROW, 0).bold().fontSize(14).set();
        ws.value(SUBTITLE_ROW, 0, subtitle);
        for (int col = 0; col < headers.size(); col++) {
            ws.value(HEADER_ROW, col, headers.get(col));
        }
        ws.range(HEADER_ROW, 0, HEADER_ROW, headers.size() - 1).style()
                .bold().fillColor("F2F2F2").borderStyle(BorderSide.BOTTOM, BorderStyle.THIN).set();
    }

    /** Frozen, filterable header repeated on every printed A4 page; {@code lastRow} is the last data row. */
    private static void pageSetup(Worksheet ws, int lastRow, int columns) {
        ws.freezePane(0, HEADER_ROW + 1);
        ws.setAutoFilter(HEADER_ROW, 0, lastRow, columns - 1);
        ws.repeatRows(HEADER_ROW);
        ws.paperSize(PaperSize.A4_PAPER);
    }

    private static <T> List<String> column(List<T> rows, Function<T, String> value) {
        return rows.stream().map(value).toList();
    }

    /** Roughly one unit per character, plus padding for the autofilter button. */
    static int width(String header, List<String> values) {
        int longest = header.length();
        for (String value : values) {
            if (value != null) {
                longest = Math.max(longest, value.length());
            }
        }
        return Math.clamp(longest + 3, MIN_WIDTH, MAX_WIDTH);
    }
}
