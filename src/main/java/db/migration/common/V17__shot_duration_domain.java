package db.migration.common;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes the original video-provider clip limit from authored shot duration. */
public class V17__shot_duration_domain extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        List<String> constraints = new ArrayList<>();
        String query = "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc " +
                "JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog " +
                "AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name " +
                "WHERE LOWER(tc.table_name)='shot' AND tc.constraint_schema=CURRENT_SCHEMA AND tc.constraint_type='CHECK'";
        try (Statement statement=context.getConnection().createStatement(); ResultSet rows=statement.executeQuery(query)) {
            while (rows.next()) {
                String clause=rows.getString(2);
                if(isOriginalDurationConstraint(clause))constraints.add(rows.getString(1));
            }
        }
        if(constraints.size()!=1)throw new IllegalStateException("Expected exactly one original shot duration constraint, found "+constraints.size());
        try (Statement statement=context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE \"shot\" DROP CONSTRAINT \""+constraints.getFirst().replace("\"","\"\"")+"\"");
            statement.execute("ALTER TABLE \"shot\" ADD CONSTRAINT ck_shot_duration_editable CHECK (duration >= 1.25)");
        }
    }

    static boolean isOriginalDurationConstraint(String clause) {
        if (clause == null) return false;
        String lower = clause.toLowerCase().replace("\"", "");
        Set<String> allowed = Set.of("check", "duration", "between", "and", "cast", "as", "numeric", "decimal", "double", "precision", "real");
        var identifiers = Pattern.compile("[a-z_][a-z0-9_]*").matcher(lower);
        while (identifiers.find()) if (!allowed.contains(identifiers.group())) return false;
        String compact = lower.replaceAll("\\s+|[()]", "")
                .replace("check", "").replace("cast", "")
                .replaceAll("as(?:numeric|decimal|doubleprecision|real)\\d*", "")
                .replaceAll("::(?:numeric|decimal|doubleprecision|real)", "");
        return compact.equals("durationbetween2and5")
                || compact.equals("duration>=2andduration<=5")
                || compact.equals("2<=durationandduration<=5");
    }
}
