package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PostgresV16ToCurrentMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void historicalV16SchemaAndDataUpgradeThroughCurrentMigrations() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/common").target("16").load().migrate();
        Fixture fixture;
        try (Connection connection = connection()) { fixture = insertLegacyData(connection); }

        Flyway flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/common").load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("26");
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT semantic_text,spoken_text,subtitle_text,document FROM \"dialogue_line\" WHERE id=?")) {
                statement.setObject(1, fixture.dialogue());
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("旧语义");
                    assertThat(row.getString(2)).isEqualTo("旧发音");
                    assertThat(row.getString(3)).isEqualTo("旧语义");
                    JsonNode document = JSON.readTree(row.getString(4));
                    assertThat(document.path("semanticText").asText()).isEqualTo("旧语义");
                    assertThat(document.path("spokenText").asText()).isEqualTo("旧发音");
                    assertThat(document.path("subtitleText").asText()).isEqualTo("旧语义");
                }
            }
            execute(connection, "UPDATE \"shot\" SET duration=8 WHERE id=?", fixture.shot());
            execute(connection, "INSERT INTO \"generation_job\"(id,project_id,parent_id,revision,created_at,updated_at,document,shot_id,type,status,attempts,max_attempts,progress,cost,cancel_requested) VALUES (?,?,?,1,?,?,?,?,'STORY','UNKNOWN',0,3,0,0,FALSE)",
                    UUID.randomUUID(), fixture.project(), fixture.project(), OffsetDateTime.now(), OffsetDateTime.now(), "{}", fixture.shot());
        }
    }

    private static Fixture insertLegacyData(Connection connection) throws Exception {
        UUID project=UUID.randomUUID(),episode=UUID.randomUUID(),scene=UUID.randomUUID(),shot=UUID.randomUUID(),character=UUID.randomUUID(),dialogue=UUID.randomUUID();OffsetDateTime now=OffsetDateTime.now();
        execute(connection,"INSERT INTO \"project\"(id,project_id,parent_id,revision,created_at,updated_at,document) VALUES (?,?,NULL,1,?,?,?)",project,project,now,now,"{}");
        execute(connection,"INSERT INTO \"episode\"(id,project_id,parent_id,revision,created_at,updated_at,document) VALUES (?,?,?,1,?,?,?)",episode,project,project,now,now,"{}");
        execute(connection,"INSERT INTO \"scene\"(id,project_id,parent_id,revision,created_at,updated_at,document,episode_id) VALUES (?,?,?,1,?,?,?,?)",scene,project,episode,now,now,"{}",episode);
        execute(connection,"INSERT INTO \"shot\"(id,project_id,parent_id,revision,created_at,updated_at,document,scene_id,duration,status) VALUES (?,?,?,1,?,?,?,?,3,'DRAFT')",shot,project,scene,now,now,"{}",scene);
        execute(connection,"INSERT INTO \"character\"(id,project_id,parent_id,revision,created_at,updated_at,document,identity_locked) VALUES (?,?,?,1,?,?,?,FALSE)",character,project,project,now,now,"{}");
        execute(connection,"INSERT INTO \"dialogue_line\"(id,project_id,parent_id,revision,created_at,updated_at,document,shot_id,character_id,display_text,dialect_text,speech_text) VALUES (?,?,?,1,?,?,?,?,?,?,?,?)",dialogue,project,shot,now,now,"{\"displayText\":\"旧语义\",\"speechText\":\"旧发音\"}",shot,character,"旧语义",null,"旧发音");
        return new Fixture(project,shot,dialogue);
    }

    private static Connection connection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private static void execute(Connection connection,String sql,Object... values)throws Exception{try(PreparedStatement statement=connection.prepareStatement(sql)){for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.executeUpdate();}}
    private record Fixture(UUID project,UUID shot,UUID dialogue){}
}
