package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionFoundationMigrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String URL="jdbc:h2:mem:revision-v28;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test void existingEntitiesReceiveBaselineVersionsAndKnownLegacyMediaReceivesSnapshot() throws Exception {
        Flyway.configure().dataSource(URL,"sa","").locations("classpath:db/migration/common").target("26").load().migrate();
        UUID project=UUID.randomUUID(),episode=UUID.randomUUID(),scene=UUID.randomUUID(),shot=UUID.randomUUID(),character=UUID.randomUUID(),look=UUID.randomUUID(),location=UUID.randomUUID(),prop=UUID.randomUUID(),prompt=UUID.randomUUID(),frame=UUID.randomUUID();Instant now=Instant.now();
        try(Connection c=DriverManager.getConnection(URL,"sa","")){
            insert(c,"project",project,project,null,doc(project,project,null).put("name","legacy"),"",now);
            insert(c,"episode",episode,project,project,doc(episode,project,project).put("episodeNo",1),"",now);
            insert(c,"scene",scene,project,episode,doc(scene,project,episode).put("episodeId",episode.toString()),",episode_id",now,episode);
            insert(c,"shot",shot,project,scene,doc(shot,project,scene).put("sceneId",scene.toString()).put("duration",3).put("status","DRAFT"),",scene_id,duration,status",now,scene,3,"DRAFT");
            insert(c,"character",character,project,project,doc(character,project,project).put("name","林川").put("ageDefinition",28),",identity_locked",now,false);
            insert(c,"character_look",look,project,character,doc(look,project,character).put("characterId",character.toString()).put("name","便装"),",character_id",now,character);
            insert(c,"location",location,project,project,doc(location,project,project).put("name","客厅"),"",now);
            insert(c,"prop",prop,project,project,doc(prop,project,project).put("name","手机"),"",now);
            insert(c,"prompt_version",prompt,project,shot,doc(prompt,project,shot).put("shotId",shot.toString()).put("version",1),",shot_id,version",now,shot,1);
            ObjectNode frameDoc=doc(frame,project,shot).put("shotId",shot.toString()).put("version",1).put("provider","MOCK").put("providerUrl","https://media.example/legacy.png").put("handoffStatus","READY").put("qcStatus","PASSED").put("selected",true).put("locked",true);
            ObjectNode input=frameDoc.putObject("generationInputSnapshot").put("promptVersionId",prompt.toString()).put("scriptVersionId",episode.toString()).put("continuitySnapshotHash","legacy-continuity");input.putArray("characterIds").add(character.toString());input.putArray("characterLookIds").add(look.toString());input.putArray("locationIds").add(location.toString());input.putArray("propIds").add(prop.toString());
            insert(c,"keyframe",frame,project,shot,frameDoc,",shot_id,version,provider,provider_url,handoff_status,qc_status,selected,locked",now,shot,1,"MOCK","https://media.example/legacy.png","READY","PASSED",true,true);
        }

        Flyway flyway=Flyway.configure().dataSource(URL,"sa","").locations("classpath:db/migration/common").target("28").load();flyway.migrate();
        try(Connection c=DriverManager.getConnection(URL,"sa","")){
            assertThat(count(c,"character_definition_version")).isEqualTo(1);
            assertThat(count(c,"location_definition_version")).isEqualTo(1);
            assertThat(count(c,"prop_definition_version")).isEqualTo(1);
            JsonNode lookDoc=document(c,"character_look",look);assertThat(lookDoc.path("version").asInt()).isEqualTo(1);assertThat(lookDoc.path("changeReason").asText()).isEqualTo("MIGRATED_BASELINE");
            JsonNode migratedFrame=document(c,"keyframe",frame);assertThat(migratedFrame.path("productionInputSnapshotId").asText()).isNotBlank();assertThat(migratedFrame.path("assetSnapshotHash").asText()).hasSize(64);
            assertThat(count(c,"production_input_snapshot")).isEqualTo(1);
        }
    }

    private static void insert(Connection c,String table,UUID id,UUID project,UUID parent,JsonNode document,String extraColumns,Instant now,Object... extra)throws Exception{
        String placeholders=",?".repeat(extra.length);String sql="INSERT INTO \""+table+"\"(id,project_id,parent_id,revision,created_at,updated_at,document"+extraColumns+") VALUES (?,?,?,1,?,?,?"+placeholders+")";
        Object[] args=new Object[6+extra.length];args[0]=id;args[1]=project;args[2]=parent;args[3]=now;args[4]=now;args[5]=document.toString();System.arraycopy(extra,0,args,6,extra.length);exec(c,sql,args);
    }
    private static ObjectNode doc(UUID id,UUID project,UUID parent){ObjectNode value=JSON.createObjectNode().put("id",id.toString()).put("projectId",project.toString()).put("revision",1).put("createdAt",Instant.now().toString()).put("updatedAt",Instant.now().toString());if(parent!=null)value.put("parentId",parent.toString());return value;}
    private static int count(Connection c,String table)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM \""+table+"\"")){r.next();return r.getInt(1);}}
    private static JsonNode document(Connection c,String table,UUID id)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT document FROM \""+table+"\" WHERE id=?")){s.setObject(1,id);try(ResultSet r=s.executeQuery()){r.next();return JSON.readTree(r.getString(1));}}}
    private static void exec(Connection c,String sql,Object... args)throws Exception{try(PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}}
}
