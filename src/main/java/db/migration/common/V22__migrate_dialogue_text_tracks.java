package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Backfills canonical dialogue text tracks in both indexed columns and the JSON document. */
public class V22__migrate_dialogue_text_tracks extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        String select = "SELECT id,document,display_text,dialect_text,speech_text,semantic_text,spoken_text,subtitle_text FROM \"dialogue_line\"";
        String update = "UPDATE \"dialogue_line\" SET document=?,semantic_text=?,spoken_text=?,subtitle_text=? WHERE id=?";
        try (Statement statement = context.getConnection().createStatement();
             ResultSet rows = statement.executeQuery(select);
             PreparedStatement write = context.getConnection().prepareStatement(update)) {
            while (rows.next()) {
                ObjectNode document = readDocument(rows.getString("document"), rows.getString("id"));
                String semantic = first(value(document, "semanticText"), rows.getString("semantic_text"),
                        value(document, "displayText"), rows.getString("display_text"));
                String subtitle = first(value(document, "subtitleText"), rows.getString("subtitle_text"),
                        value(document, "displayText"), rows.getString("display_text"), semantic);
                String spoken = first(value(document, "spokenText"), rows.getString("spoken_text"),
                        value(document, "speechText"), rows.getString("speech_text"),
                        value(document, "dialectText"), rows.getString("dialect_text"),
                        value(document, "displayText"), rows.getString("display_text"), semantic);

                putIfMissing(document, "semanticText", semantic);
                putIfMissing(document, "spokenText", spoken);
                putIfMissing(document, "subtitleText", subtitle);
                write.setString(1, document.toString());
                write.setString(2, semantic);
                write.setString(3, spoken);
                write.setString(4, subtitle);
                write.setObject(5, rows.getObject("id"));
                write.addBatch();
            }
            write.executeBatch();
        }
    }

    private static ObjectNode readDocument(String json, String id) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node instanceof ObjectNode object) return object;
            throw new IllegalStateException("Dialogue document is not a JSON object: " + id);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot migrate dialogue document: " + id, exception);
        }
    }

    private static String value(ObjectNode document, String field) {
        JsonNode value = document.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String first(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    private static void putIfMissing(ObjectNode document, String field, String value) {
        if ((!document.has(field) || document.get(field).isNull()) && value != null) document.put(field, value);
    }
}
