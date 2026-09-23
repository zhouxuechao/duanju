package db.migration.common;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Keeps provider outcome uncertainty distinct from a confirmed failed generation. */
public class V19__generation_job_unknown_status extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        List<String> constraints=new ArrayList<>();
        String query="SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc " +
            "JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog " +
            "AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name " +
            "WHERE LOWER(tc.table_name)='generation_job' AND tc.constraint_schema=CURRENT_SCHEMA AND tc.constraint_type='CHECK'";
        try(Statement statement=context.getConnection().createStatement();ResultSet rows=statement.executeQuery(query)){
            while(rows.next()){
                String clause=rows.getString(2);
                if(isOriginalStatusConstraint(clause))constraints.add(rows.getString(1));
            }
        }
        if(constraints.size()!=1)throw new IllegalStateException("Expected exactly one generation_job status constraint, found "+constraints.size());
        try(Statement statement=context.getConnection().createStatement()){
            statement.execute("ALTER TABLE \"generation_job\" DROP CONSTRAINT \""+constraints.getFirst().replace("\"","\"\"")+"\"");
            statement.execute("ALTER TABLE \"generation_job\" ADD CONSTRAINT ck_generation_job_status CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILED','CANCELLED','RETRY_WAIT','UNKNOWN','WAITING_HUMAN'))");
        }
    }

    static boolean isOriginalStatusConstraint(String clause){
        if(clause==null||!Pattern.compile("(?i)\\bstatus\\b").matcher(clause.replace("\"","")).find())return false;
        Set<String> values=new LinkedHashSet<>();var matcher=Pattern.compile("'([A-Z_]+)'",Pattern.CASE_INSENSITIVE).matcher(clause);
        while(matcher.find())values.add(matcher.group(1).toUpperCase());
        return values.equals(Set.of("QUEUED","RUNNING","SUCCESS","FAILED","CANCELLED","RETRY_WAIT"));
    }
}
