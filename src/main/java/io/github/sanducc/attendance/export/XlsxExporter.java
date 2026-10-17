package io.github.sanducc.attendance.export;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.dhatim.fastexcel.BorderSide;
import org.dhatim.fastexcel.BorderStyle;
import org.dhatim.fastexcel.PaperSize;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * A real .xlsx (one sheet, "Attendance"): title block, then a bold, frozen, filterable header and one row per
 * check-in. Names and emails are written as plain string cells, which Excel never evaluates as formulas; the
 * check-in time is a real date/time cell shown as HH:mm:ss. The last column says "scan" or (bold) "manual".
 */
public final class XlsxExporter {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String SHEET_NAME = "Attendance";

    static final int TITLE_ROW = 0;
    static final int SUBTITLE_ROW = 1;
    static final int HEADER_ROW = 3;
    static final String TIME_FORMAT = "HH:mm:ss";

    private static final int COL_NR = 0;
    private static final int COL_LAST = 1;
    private static final int COL_FIRST = 2;
    private static final int COL_EMAIL = 3;
    private static final int COL_TIME = 4;
    private static final int COL_SOURCE = 5;
    private static final int MIN_WIDTH = 6;
    private static final int MAX_WIDTH = 50;

    private XlsxExporter() {
    }

    public static void write(OutputStream out, AttendanceSheet sheet) throws IOException {
        Workbook workbook = new Workbook(out, "qr-attendance", "1.0");
        Worksheet ws = workbook.newWorksheet(SHEET_NAME);

        ws.value(TITLE_ROW, 0, sheet.title());
        ws.style(TITLE_ROW, 0).bold().fontSize(14).set();
        ws.value(SUBTITLE_ROW, 0, sheet.subtitle());

        List<String> headers = AttendanceSheet.HEADERS;
        for (int col = 0; col < headers.size(); col++) {
            ws.value(HEADER_ROW, col, headers.get(col));
        }
        ws.range(HEADER_ROW, 0, HEADER_ROW, headers.size() - 1).style()
                .bold().fillColor("F2F2F2").borderStyle(BorderSide.BOTTOM, BorderStyle.THIN).set();

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

        ws.freezePane(0, HEADER_ROW + 1);
        ws.setAutoFilter(HEADER_ROW, 0, row, headers.size() - 1);
        ws.repeatRows(HEADER_ROW);
        ws.paperSize(PaperSize.A4_PAPER);

        ws.width(COL_NR, width(headers.get(COL_NR), sheet.rows().stream().map(r -> Integer.toString(r.nr()))
                .toList()));
        ws.width(COL_LAST, width(headers.get(COL_LAST), sheet.rows().stream().map(AttendanceSheet.Row::lastName)
                .toList()));
        ws.width(COL_FIRST, width(headers.get(COL_FIRST), sheet.rows().stream().map(AttendanceSheet.Row::firstName)
                .toList()));
        ws.width(COL_EMAIL, width(headers.get(COL_EMAIL), sheet.rows().stream().map(AttendanceSheet.Row::email)
                .toList()));
        ws.width(COL_TIME, width(headers.get(COL_TIME), List.of(TIME_FORMAT)));
        ws.width(COL_SOURCE, width(headers.get(COL_SOURCE), List.of(AttendanceSheet.SOURCE_MANUAL)));

        workbook.finish();
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
