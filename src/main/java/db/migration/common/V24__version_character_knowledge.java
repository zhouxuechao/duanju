package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Converts one-row character knowledge into immutable story-time versions. */
public class V24__version_character_knowledge extends BaseJavaMigration {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Override public void migrate(Context context)throws Exception{
        try(Statement sql=context.getConnection().createStatement()){
            sql.execute("ALTER TABLE \"character_knowledge\" DROP CONSTRAINT uq_character_knowledge_fact");
            sql.execute("ALTER TABLE \"character_knowledge\" ADD COLUMN valid_from_story_time NUMERIC");
            sql.execute("ALTER TABLE \"character_knowledge\" ADD COLUMN valid_to_story_time NUMERIC");
            sql.execute("ALTER TABLE \"character_knowledge\" ADD COLUMN believed_statement TEXT");
            sql.execute("UPDATE \"character_knowledge\" SET valid_from_story_time=known_from_story_time");
            sql.execute("ALTER TABLE \"character_knowledge\" ALTER COLUMN valid_from_story_time SET NOT NULL");
            sql.execute("ALTER TABLE \"character_knowledge\" ADD CONSTRAINT ck_character_knowledge_range CHECK(valid_to_story_time IS NULL OR valid_to_story_time>valid_from_story_time)");
            sql.execute("ALTER TABLE \"character_knowledge\" ADD CONSTRAINT uq_character_knowledge_time UNIQUE(project_id,character_id,fact_id,valid_from_story_time)");
            sql.execute("CREATE INDEX ix_character_knowledge_timeline ON \"character_knowledge\"(project_id,character_id,fact_id,valid_from_story_time,valid_to_story_time)");
        }
        try(Statement statement=context.getConnection().createStatement();ResultSet rows=statement.executeQuery("SELECT id,document,known_from_story_time FROM \"character_knowledge\"");PreparedStatement update=context.getConnection().prepareStatement("UPDATE \"character_knowledge\" SET document=?,believed_statement=? WHERE id=?")){
            while(rows.next()){
                JsonNode parsed=JSON.readTree(rows.getString("document"));if(!(parsed instanceof ObjectNode document))throw new IllegalStateException("CharacterKnowledge document is not an object: "+rows.getObject("id"));
                if(!document.path("validFromStoryTime").isNumber())document.set("validFromStoryTime",JSON.valueToTree(rows.getBigDecimal("known_from_story_time")));
                if(!document.path("knownFromStoryTime").isNumber())document.set("knownFromStoryTime",document.path("validFromStoryTime").deepCopy());
                update.setString(1,document.toString());update.setString(2,document.path("believedStatement").isTextual()?document.path("believedStatement").asText():null);update.setObject(3,rows.getObject("id"));update.addBatch();
            }update.executeBatch();
        }
    }
}
