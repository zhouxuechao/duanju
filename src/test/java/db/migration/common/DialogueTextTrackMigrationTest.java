package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueTextTrackMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "jdbc:h2:mem:dialogue-v22;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void migratesLegacyColumnsAndDocumentJsonWithoutOverwritingCanonicalTracks() throws Exception {
        Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration/common").target("21").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            Fixture fixture = insertHierarchy(connection);
            Map<String, String> documents = new LinkedHashMap<>();
            documents.put("legacy-all", "{\"displayText\":\"你今天去哪里了？\",\"speechText\":\"谐音发音\",\"dialectText\":\"耒阳方言表达\"}");
            documents.put("display-only", "{\"displayText\":\"只显示这一句\"}");
            documents.put("canonical-only", "{\"semanticText\":\"新语义\",\"spokenText\":\"新发音\",\"subtitleText\":\"新字幕\"}");
            documents.put("mixed", "{\"semanticText\":\"保留语义\",\"spokenText\":\"保留发音\",\"subtitleText\":\"保留字幕\",\"displayText\":\"旧显示\",\"speechText\":\"旧发音\",\"dialectText\":\"旧方言\"}");
            for (var entry : documents.entrySet()) insertDialogue(connection, fixture, entry.getKey(), entry.getValue());
        }

        Flyway flyway = Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration/common").target("22").load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            assertTracks(connection, "legacy-all", "你今天去哪里了？", "谐音发音", "你今天去哪里了？");
            assertTracks(connection, "display-only", "只显示这一句", "只显示这一句", "只显示这一句");
            assertTracks(connection, "canonical-only", "新语义", "新发音", "新字幕");
            assertTracks(connection, "mixed", "保留语义", "保留发音", "保留字幕");
        }
    }

    private static Fixture insertHierarchy(Connection connection) throws Exception {
        UUID project = UUID.randomUUID();
        UUID episode = UUID.randomUUID();
        UUID scene = UUID.randomUUID();
        UUID shot = UUID.randomUUID();
        UUID character = UUID.randomUUID();
        Instant now = Instant.now();
        execute(connection, "INSERT INTO \"project\"(id,project_id,parent_id,revision,created_at,updated_at,document) VALUES (?,?,NULL,1,?,?,?)",
                project, project, now, now, "{\"id\":\"" + project + "\"}");
        execute(connection, "INSERT INTO \"episode\"(id,project_id,parent_id,revision,created_at,updated_at,document) VALUES (?,?,?,1,?,?,?)",
                episode, project, project, now, now, "{\"id\":\"" + episode + "\"}");
        execute(connection, "INSERT INTO \"scene\"(id,project_id,parent_id,revision,created_at,updated_at,document,episode_id) VALUES (?,?,?,1,?,?,?,?)",
                scene, project, episode, now, now, "{\"id\":\"" + scene + "\"}", episode);
        execute(connection, "INSERT INTO \"shot\"(id,project_id,parent_id,revision,created_at,updated_at,document,scene_id,duration,status) VALUES (?,?,?,1,?,?,?,?,3,'DRAFT')",
                shot, project, scene, now, now, "{\"id\":\"" + shot + "\"}", scene);
        execute(connection, "INSERT INTO \"character\"(id,project_id,parent_id,revision,created_at,updated_at,document,identity_locked) VALUES (?,?,?,1,?,?,?,FALSE)",
                character, project, project, now, now, "{\"id\":\"" + character + "\"}");
        return new Fixture(project, shot, character, now);
    }

    private static void insertDialogue(Connection connection, Fixture fixture, String marker, String document) throws Exception {
        JsonNode node = JSON.readTree(document);
        execute(connection, "INSERT INTO \"dialogue_line\"(id,project_id,parent_id,revision,created_at,updated_at,document,shot_id,character_id,display_text,dialect_text,speech_text,semantic_text,spoken_text,subtitle_text) VALUES (?,?,?,1,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.nameUUIDFromBytes(marker.getBytes()), fixture.project(), fixture.shot(), fixture.now(), fixture.now(), document,
                fixture.shot(), fixture.character(), text(node, "displayText"), text(node, "dialectText"), text(node, "speechText"),
                text(node, "semanticText"), text(node, "spokenText"), text(node, "subtitleText"));
    }

    private static void assertTracks(Connection connection, String marker, String semantic, String spoken, String subtitle) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT semantic_text,spoken_text,subtitle_text,document FROM \"dialogue_line\" WHERE id=?")) {
            statement.setObject(1, UUID.nameUUIDFromBytes(marker.getBytes()));
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(semantic);
                assertThat(row.getString(2)).isEqualTo(spoken);
                assertThat(row.getString(3)).isEqualTo(subtitle);
                JsonNode document = JSON.readTree(row.getString(4));
                assertThat(document.path("semanticText").asText()).isEqualTo(semantic);
                assertThat(document.path("spokenText").asText()).isEqualTo(spoken);
                assertThat(document.path("subtitleText").asText()).isEqualTo(subtitle);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... args) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private record Fixture(UUID project, UUID shot, UUID character, Instant now) {}
}
