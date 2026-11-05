package com.hengce.aps.service.importexport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Excel 模板生成器：为 9 类实体生成可直接被 Excel 打开的 xlsx 模板。
 *
 * <p>工作表：{@code 数据} 放表头与列批注；{@code 字段说明} 放列键、必填、类型与说明。</p>
 */
@Component
public class ExcelTemplateWriter {

    private static final int MAX_COLUMN_WIDTH = 40;

    /**
     * 生成模板字节流。
     *
     * @param entity 实体
     * @return xlsx 字节
     */
    public byte[] write(ImportEntity entity) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeDataSheet(workbook, workbook.createSheet("数据"), entity);
            writeHelpSheet(workbook, workbook.createSheet("字段说明"), entity);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成 Excel 模板失败: " + entity.slug(), exception);
        }
    }

    /**
     * 模板文件名。
     *
     * @param entity 实体
     * @return 文件名
     */
    public String fileName(ImportEntity entity) {
        return entity.slug() + "-import-template.xlsx";
    }

    private void writeDataSheet(Workbook workbook, Sheet sheet, ImportEntity entity) {
        CellStyle headerStyle = headerStyle(workbook);
        Row header = sheet.createRow(0);
        for (int index = 0; index < entity.columns().size(); index++) {
            ImportColumn column = entity.columns().get(index);
            Cell cell = header.createCell(index);
            cell.setCellValue(column.header() + (column.required() ? " *" : ""));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, Math.min(MAX_COLUMN_WIDTH * 256, (column.header().length() + 8) * 256));
            addComment(sheet, cell, column.description());
        }
    }

    private void writeHelpSheet(Workbook workbook, Sheet sheet, ImportEntity entity) {
        CellStyle headerStyle = headerStyle(workbook);
        String[] titles = {"列键", "表头", "必填", "类型", "可选值", "示例", "说明"};
        Row header = sheet.createRow(0);
        for (int index = 0; index < titles.length; index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(titles[index]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, 30 * 256);
        }
        for (int rowIndex = 0; rowIndex < entity.columns().size(); rowIndex++) {
            ImportColumn column = entity.columns().get(rowIndex);
            Row row = sheet.createRow(rowIndex + 1);
            row.createCell(0).setCellValue(column.key());
            row.createCell(1).setCellValue(column.header());
            row.createCell(2).setCellValue(column.required() ? "是" : "否");
            row.createCell(3).setCellValue(column.type().name());
            row.createCell(4).setCellValue(String.join("/", column.allowedValues()));
            row.createCell(5).setCellValue(column.example());
            row.createCell(6).setCellValue(column.description());
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private void addComment(Sheet sheet, Cell cell, String text) {
        if (sheet instanceof XSSFSheet xssfSheet) {
            var drawing = xssfSheet.createDrawingPatriarch();
            var anchor = drawing.createAnchor(0, 0, 0, 0, cell.getColumnIndex(), cell.getRowIndex() + 1,
                    cell.getColumnIndex() + 2, cell.getRowIndex() + 4);
            var comment = drawing.createCellComment(anchor);
            comment.setString(new org.apache.poi.xssf.usermodel.XSSFRichTextString(text));
            cell.setCellComment(comment);
        }
    }
}
