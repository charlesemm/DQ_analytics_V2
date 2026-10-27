package ci.cnam.dqanalyzer.profiling.domain;

import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import ci.cnam.dqanalyzer.profiling.ColumnProfile.ValueCount;
import ci.cnam.dqanalyzer.rules.Dataset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Calcule le profil de chaque colonne d'un fichier importé, en 3 requêtes DuckDB par colonne.
 * Un type est retenu quand au moins 95 % des cellules remplies s'y convertissent : un fichier réel contient
 * toujours quelques valeurs mal saisies, et ce sont justement elles que les règles doivent trouver.
 */
@Component
public class ColumnProfiler {

    private static final double TYPE_SHARE = 0.95;

    public List<ColumnProfile> profile(Dataset data) throws SQLException {
        var result = new ArrayList<ColumnProfile>();
        for (String column : data.columns()) {
            result.add(profile(data, column));
        }
        return result;
    }

    private ColumnProfile profile(Dataset data, String column) throws SQLException {
        String c = data.quotedColumn(column);
        String v = "trim(CAST(" + c + " AS VARCHAR))";
        String filled = c + " IS NOT NULL AND " + v + " <> ''";
        String f = " FILTER (WHERE " + filled + ")";
        String stats = "SELECT count(*), count(*)" + f + ", count(DISTINCT " + v + ")" + f + ","
                + " min(length(" + v + "))" + f + ", max(length(" + v + "))" + f + ","
                + " min(" + v + ")" + f + ", max(" + v + ")" + f + ","
                + " count(TRY_CAST(" + v + " AS BIGINT)), count(TRY_CAST(replace(" + v + ", ',', '.') AS DOUBLE)),"
                + " count(try_strptime(" + v + ", '%d/%m/%Y')) + count(try_strptime(" + v + ", '%Y-%m-%d'))"
                + " FROM " + data.quotedTable();
        try (var st = data.connection().createStatement();
                var rs = st.executeQuery(stats)) {
            rs.next();
            long rows = rs.getLong(1);
            long filledCount = rs.getLong(2);
            String type = detectType(filledCount, rs.getLong(8), rs.getLong(9), rs.getLong(10));
            return new ColumnProfile(
                    column,
                    rows,
                    filledCount,
                    rs.getLong(3),
                    nullableInt(rs, 4),
                    nullableInt(rs, 5),
                    rs.getString(6),
                    rs.getString(7),
                    type,
                    counts(data, v, filled, 10),
                    counts(
                            data,
                            "regexp_replace(regexp_replace(regexp_replace(" + v + ", '[0-9]', '9', 'g'),"
                                    + " '[A-Z]', 'A', 'g'), '[a-z]', 'a', 'g')",
                            filled,
                            5));
        }
    }

    private static Integer nullableInt(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : (int) value;
    }

    private static String detectType(long filled, long integers, long decimals, long dates) {
        if (filled == 0) {
            return "VIDE";
        }
        if (integers >= TYPE_SHARE * filled) {
            return "ENTIER";
        }
        if (decimals >= TYPE_SHARE * filled) {
            return "DECIMAL";
        }
        if (dates >= TYPE_SHARE * filled) {
            return "DATE";
        }
        return "TEXTE";
    }

    private static List<ValueCount> counts(Dataset data, String expression, String filled, int limit)
            throws SQLException {
        String sql = "SELECT " + expression + " AS val, count(*) AS n FROM " + data.quotedTable() + " WHERE " + filled
                + " GROUP BY 1 ORDER BY n DESC, val LIMIT " + limit;
        var result = new ArrayList<ValueCount>();
        try (var st = data.connection().createStatement();
                var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new ValueCount(rs.getString(1), rs.getLong(2)));
            }
        }
        return result;
    }
}
