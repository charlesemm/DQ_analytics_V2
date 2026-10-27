package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;

/**
 * Lecture d'un classeur Excel 97-2003 (.xls). C'est le seul format que l'outil charge en mémoire :
 * le format ne se lit pas en flux, et une feuille y est de toute façon limitée à 65 536 lignes. Le
 * CDC (§5.1 bis) fixe pour cette raison une taille maximale de 100 Mo, contrôlée avant d'arriver ici.
 *
 * <p>Comme pour les autres tableurs, la valeur retenue est celle qu'Excel affiche : une date s'écrit
 * « 31/12/2024 » et le résultat mémorisé d'une formule remplace la formule elle-même.
 */
public final class XlsRowSource extends SheetRowSource {

    private final HSSFWorkbook workbook;
    private final Iterator<org.apache.poi.ss.usermodel.Row> rows;
    private final DataFormatter formatter = new DataFormatter(Locale.FRANCE);
    private final long lastRowNumber;

    private XlsRowSource(HSSFWorkbook workbook, Sheet sheet) {
        this.workbook = workbook;
        this.rows = sheet.rowIterator();
        this.lastRowNumber = sheet.getLastRowNum() + 1L;
    }

    /**
     * Ouvre le classeur, se place sur la feuille demandée et lit sa ligne d'en-tête.
     *
     * @throws ImportRejectedException si le classeur est illisible, la feuille introuvable ou la
     *     ligne d'en-tête absente
     */
    public static XlsRowSource open(Path file, SheetSettings settings) {
        HSSFWorkbook workbook = null;
        try {
            workbook = new HSSFWorkbook(new POIFSFileSystem(file.toFile(), true));
            var source = new XlsRowSource(workbook, sheet(workbook, settings.sheetName()));
            source.readHeader(settings);
            return source;
        } catch (ImportRejectedException e) {
            closeQuietly(workbook);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(workbook);
            throw new ImportRejectedException("import.error.classeur-illisible");
        }
    }

    /** Noms des feuilles du classeur, dans l'ordre : de quoi proposer le choix à l'analyste (IMP-02). */
    public static List<String> sheetNames(Path file) {
        try (var workbook = new HSSFWorkbook(new POIFSFileSystem(file.toFile(), true))) {
            var names = new ArrayList<String>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                names.add(workbook.getSheetName(index));
            }
            return List.copyOf(names);
        } catch (IOException | RuntimeException e) {
            throw new ImportRejectedException("import.error.classeur-illisible");
        }
    }

    @Override
    protected Row nextRow() {
        while (rows.hasNext()) {
            var row = rows.next();
            var values = values(row);
            if (!isEmpty(values)) {
                return new Row(row.getRowNum() + 1L, values);
            }
        }
        return null;
    }

    @Override
    public int percent() {
        var current = currentRow();
        if (lastRowNumber <= 0 || current == null) {
            return 0;
        }
        return (int) Math.min(MAX_PERCENT_BEFORE_END, current.number() * 100 / lastRowNumber);
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }

    /** Cellules de la ligne, rangées à leur position ; les cellules absentes restent nulles. */
    private List<String> values(org.apache.poi.ss.usermodel.Row row) {
        int width = Math.max(row.getLastCellNum(), 0);
        var values = new ArrayList<String>(width);
        for (int column = 0; column < width; column++) {
            values.add(value(row.getCell(column)));
        }
        return values;
    }

    private String value(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case BLANK -> null;
            case BOOLEAN -> bool(cell.getBooleanCellValue());
            case FORMULA -> cachedResult(cell);
            default -> formatter.formatCellValue(cell);
        };
    }

    /** Résultat mémorisé par Excel lors du dernier calcul : l'outil ne recalcule aucune formule. */
    private String cachedResult(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC ->
                formatter.formatRawCellContents(
                        cell.getNumericCellValue(),
                        cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString());
            case STRING -> cell.getRichStringCellValue().getString();
            case BOOLEAN -> bool(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private static String bool(boolean value) {
        return value ? "VRAI" : "FAUX";
    }

    private static Sheet sheet(HSSFWorkbook workbook, String sheetName) {
        if (workbook.getNumberOfSheets() == 0) {
            throw new ImportRejectedException("import.error.classeur-sans-feuille");
        }
        if (sheetName == null) {
            return workbook.getSheetAt(0);
        }
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            if (sheetName.equalsIgnoreCase(workbook.getSheetName(index))) {
                return workbook.getSheetAt(index);
            }
        }
        throw new ImportRejectedException("import.error.feuille-introuvable", sheetName);
    }

    private static void closeQuietly(HSSFWorkbook workbook) {
        if (workbook != null) {
            try {
                workbook.close();
            } catch (IOException e) {
                // Classeur ouvert en lecture seule : rien de plus à tenter.
            }
        }
    }
}
