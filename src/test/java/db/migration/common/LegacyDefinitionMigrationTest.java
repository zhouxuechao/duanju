package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyDefinitionMigrationTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void rewritesPersistedDirectorFieldsAndDirectionsWithoutRuntimeAliases() throws Exception {
        String old="""
            {"activeShotPlanJobId":"job-1","activeShotPlanSignature":"sig","shotPlanStatus":"SUCCESS",
             "nested":[{"screenDirection":"正向画面深处"},{"screenDirection":"右前方"},
                       {"screenDirection":"左前方"},{"screenDirection":"正向镜头方向"}]}
            """;
        JsonNode migrated=mapper.readTree(V13__canonical_director_definitions.canonicalizeDocument(old));
        assertThat(migrated.has("activeShotPlanJobId")||migrated.has("activeShotPlanSignature")||migrated.has("shotPlanStatus")).isFalse();
        assertThat(migrated.path("activeDirectorPlanJobId").asText()).isEqualTo("job-1");
        assertThat(migrated.path("activeDirectorPlanSignature").asText()).isEqualTo("sig");
        assertThat(migrated.path("directorPlanStatus").asText()).isEqualTo("SUCCESS");
        assertThat(migrated.path("nested").findValuesAsText("screenDirection"))
            .containsExactly("INTO_DEPTH","FRAME_RIGHT","FRAME_LEFT","OUT_OF_DEPTH");
    }

    @Test void refusesUnknownPersistedDirectionInsteadOfGuessing(){
        assertThatThrownBy(()->V13__canonical_director_definitions.canonicalizeDocument("{\"screenDirection\":\"斜着走\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("斜着走");
    }

    @Test void historicalAuditRowsKeepFreeTextDirectionsWithoutEnteringCurrentContract() throws Exception {
        String old="{\"providerOutput\":{\"screenDirection\":\"身体朝右前，脸向左前\"}}";
        JsonNode history=mapper.readTree(V13__canonical_director_definitions.canonicalizeHistoricalDocument(old));
        assertThat(history.path("providerOutput").path("screenDirection").asText()).isEqualTo("身体朝右前，脸向左前");
    }

    @Test void scenePointingAtRetiredShotPlanIsMarkedForReplanning() throws Exception {
        String old="{\"activeShotPlanJobId\":\"old-job\",\"activeShotPlanSignature\":\"sig\",\"shotPlanStatus\":\"FAILED\"}";
        JsonNode scene=mapper.readTree(V13__canonical_director_definitions.canonicalizeSceneDocument(old,java.util.Set.of("current-job")));
        assertThat(scene.has("activeShotPlanJobId")||scene.has("activeDirectorPlanJobId")).isFalse();
        assertThat(scene.path("directorPlanStatus").asText()).isEqualTo("REPLAN_REQUIRED");
    }
}
