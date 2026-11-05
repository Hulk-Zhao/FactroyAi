package com.hengce.aps.service.importexport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 数据导出写入器：列顺序与导入模板“数据”页一致，导出文件可直接回填后再次导入。
 */
public final class ExcelDataWriter {

    private ExcelDataWriter() {
    }

    /**
     * 生成数据 xlsx。
     *
     * @param entity 实体
     * @param rows 行数据，键为列键
     * @return xlsx 字节
     */
    public static byte[] write(ImportEntity entity, List<Map<String, Object>> rows) {
        return write(entity, rows, List.of());
    }

    /**
     * 生成数据 xlsx，并在实体列之后追加只读展示列。
     *
     * @param entity 实体
     * @param rows 行数据，键为列键
     * @param extraColumns 追加在末尾的只读展示列（导入时按未知列忽略）
     * @return xlsx 字节
     */
    public static byte[] write(ImportEntity entity, List<Map<String, Object>> rows,
                               List<ImportColumn> extraColumns) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("数据");
            CellStyle headerStyle = headerStyle(workbook);
            Row headerRow = sheet.createRow(0);
            List<ImportColumn> columns = new ArrayList<>(entity.columns());
            columns.addAll(extraColumns);
            for (int index = 0; index < columns.size(); index++) {
                ImportColumn column = columns.get(index);
                Cell cell = headerRow.createCell(index);
                cell.setCellValue(column.required() ? column.header() + " *" : column.header());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, 20 * 256);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                Map<String, Object> values = rows.get(rowIndex);
                for (int index = 0; index < columns.size(); index++) {
                    String value = format(values.get(columns.get(index).key()));
                    if (value != null && !value.isEmpty()) {
                        row.createCell(index).setCellValue(value);
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("导出 xlsx 失败", exception);
        }
    }

    /**
     * 导出文件名。
     *
     * @param entity 实体
     * @return 文件名
     */
    public static String fileName(ImportEntity entity) {
        return entity.slug() + "-export.xlsx";
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static String format(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        return value.toString();
    }
}
