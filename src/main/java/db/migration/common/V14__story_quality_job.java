package db.migration.common;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Adds the durable story-quality stage without reopening retired legacy job types. */
public class V14__story_quality_job extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<String> constraints = new ArrayList<>();
        String query = "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc " +
                "JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog " +
                "AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name " +
                "WHERE LOWER(tc.table_name)='generation_job' AND tc.constraint_schema=CURRENT_SCHEMA " +
                "AND tc.constraint_type='CHECK'";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                String clause = rows.getString(2);
                if (clause.contains("'ASSET_IMAGE'") && clause.contains("'SHOT_DETAIL'")) constraints.add(rows.getString(1));
            }
        }
        if (constraints.size() != 1) throw new IllegalStateException("Expected exactly one generation_job type constraint");
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE \"generation_job\" DROP CONSTRAINT \"" + constraints.getFirst().replace("\"", "\"\"") + "\"");
            statement.execute("""
                ALTER TABLE "generation_job" ADD CONSTRAINT ck_generation_job_type CHECK (
                  type IN ('STORY','SCRIPT','STORY_QA','DIRECTOR_PLAN','SHOT_DETAIL','STORYBOARD','KEYFRAME','KEYFRAME_QC','VIDEO','VIDEO_QC','TTS','LIPSYNC','TIMELINE','RENDER','ARCHIVE','ASSET_IMAGE')
                  OR (type IN ('CHARACTER_PLAN','CHARACTER_LOOK','LOCATION_LOOK','SHOT_PLAN','REPAIR') AND status IN ('SUCCESS','FAILED','CANCELLED'))
                )
                """);
        }
    }
}
