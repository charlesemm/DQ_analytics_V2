package ci.cnam.dqanalyzer.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Fabrique les classeurs Excel des tests. Ils sont écrits à la volée plutôt que déposés en binaire
 * dans le dépôt : le contenu attendu se lit alors dans le test lui-même.
 *
 * <p>{@code XSSFWorkbook} charge tout en mémoire : réservé aux tests, le code de production lit en
 * flux (PERF-03).
 */
public final class Workbooks {

    private Workbooks() {}

    /** Une feuille et son contenu ; une cellule {@code null} n'est pas écrite du tout. */
    public record Sheet(String name, List<List<Object>> rows) {}

    public static Sheet sheet(String name, List<List<Object>> rows) {
        return new Sheet(name, rows);
    }

    /** Écrit un classeur Excel 2007 et suivants (.xlsx) et renvoie son chemin. */
    public static Path write(Path file, Sheet... sheets) {
        return write(file, new XSSFWorkbook(), sheets);
    }

    /** Écrit un classeur Excel 97-2003 (.xls) et renvoie son chemin. */
    public static Path writeXls(Path file, Sheet... sheets) {
        return write(file, new HSSFWorkbook(), sheets);
    }

    private static Path write(Path file, Workbook newWorkbook, Sheet... sheets) {
        try (var workbook = newWorkbook;
                var out = Files.newOutputStream(file)) {
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(
                    workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            for (Sheet sheet : sheets) {
                var created = workbook.createSheet(sheet.name());
                for (int rowIndex = 0; rowIndex < sheet.rows().size(); rowIndex++) {
                    var values = sheet.rows().get(rowIndex);
                    var row = created.createRow(rowIndex);
                    for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                        Object value = values.get(columnIndex);
                        if (value == null) {
                            continue;
                        }
                        var cell = row.createCell(columnIndex);
                        switch (value) {
                            case String text -> cell.setCellValue(text);
                            case Boolean flag -> cell.setCellValue(flag);
                            case LocalDate date -> {
                                cell.setCellValue(date);
                                cell.setCellStyle(dateStyle);
                            }
                            case Number number -> cell.setCellValue(number.doubleValue());
                            default -> throw new IllegalArgumentException("Type de cellule non prévu : " + value);
                        }
                    }
                }
            }
            workbook.write(out);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
