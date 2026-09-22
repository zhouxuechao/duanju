package db.migration.common;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
                String clause=rows.getString(2).toLowerCase();
                if(clause.contains("duration")&&clause.contains("2")&&clause.contains("5"))constraints.add(rows.getString(1));
            }
        }
        if(constraints.size()!=1)throw new IllegalStateException("Expected exactly one original shot duration constraint, found "+constraints.size());
        try (Statement statement=context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE \"shot\" DROP CONSTRAINT \""+constraints.getFirst().replace("\"","\"\"")+"\"");
            statement.execute("ALTER TABLE \"shot\" ADD CONSTRAINT ck_shot_duration_editable CHECK (duration >= 1.25)");
        }
    }
}
