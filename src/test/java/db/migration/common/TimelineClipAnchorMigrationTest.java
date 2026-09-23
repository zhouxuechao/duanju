package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineClipAnchorMigrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String URL="jdbc:h2:mem:timeline-v26;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test void relinksUniqueLegacyClipsAndMarksAmbiguousOrMissingRows() throws Exception {
        Flyway.configure().dataSource(URL,"sa","").locations("classpath:db/migration/common").target("24").load().migrate();
        UUID project=UUID.randomUUID(),episode=UUID.randomUUID(),scene=UUID.randomUUID(),shotA=UUID.randomUUID(),shotB=UUID.randomUUID(),timeline=UUID.randomUUID(),videoA=UUID.randomUUID(),videoB1=UUID.randomUUID(),videoB2=UUID.randomUUID();Instant now=Instant.now();
        try(Connection c=DriverManager.getConnection(URL,"sa","")){
            exec(c,"INSERT INTO \"project\"(id,project_id,parent_id,revision,created_at,updated_at,document) VALUES (?,?,NULL,1,?,?,?)",project,project,now,now,json(project,project,null));
            exec(c,"INSERT INTO \"episode\"(id,project_id,parent_id,revision,created_at,updated_at,document) VALUES (?,?,?,1,?,?,?)",episode,project,project,now,now,json(episode,project,project));
            exec(c,"INSERT INTO \"scene\"(id,project_id,parent_id,revision,created_at,updated_at,document,episode_id) VALUES (?,?,?,1,?,?,?,?)",scene,project,episode,now,now,json(scene,project,episode),episode);
            for(UUID shot:new UUID[]{shotA,shotB})exec(c,"INSERT INTO \"shot\"(id,project_id,parent_id,revision,created_at,updated_at,document,scene_id,duration,status) VALUES (?,?,?,1,?,?,?,?,3,'DRAFT')",shot,project,scene,now,now,json(shot,project,scene),scene);
            exec(c,"INSERT INTO \"timeline\"(id,project_id,parent_id,revision,created_at,updated_at,document,episode_id) VALUES (?,?,?,1,?,?,?,?)",timeline,project,episode,now,now,json(timeline,project,episode),episode);
            insertItem(c,videoA,project,timeline,shotA,"VIDEO",now);insertItem(c,videoB1,project,timeline,shotB,"VIDEO",now);insertItem(c,videoB2,project,timeline,shotB,"VIDEO",now);
            insertItem(c,UUID.nameUUIDFromBytes("unique".getBytes()),project,timeline,shotA,"DIALOGUE",now);
            insertItem(c,UUID.nameUUIDFromBytes("ambiguous".getBytes()),project,timeline,shotB,"SFX",now);
            insertItem(c,UUID.nameUUIDFromBytes("missing".getBytes()),project,timeline,null,"BGM",now);
            insertItem(c,UUID.nameUUIDFromBytes("ambience".getBytes()),project,timeline,null,"AMBIENCE",now);
            insertItem(c,UUID.nameUUIDFromBytes("global-sfx".getBytes()),project,timeline,null,"SFX",now);
            insertItem(c,UUID.nameUUIDFromBytes("dialogue-missing".getBytes()),project,timeline,null,"DIALOGUE",now);
            insertLinkedItem(c,UUID.nameUUIDFromBytes("existing".getBytes()),project,timeline,shotB,"DIALOGUE",videoB1,now);
        }
        Flyway v25=Flyway.configure().dataSource(URL,"sa","").locations("classpath:db/migration/common").target("25").load();v25.migrate();
        try(Connection c=DriverManager.getConnection(URL,"sa","")){assertThat(document(c,"missing").path("timelineRelinkReason").asText()).isEqualTo("VIDEO_CLIP_NOT_FOUND");}
        Flyway flyway=Flyway.configure().dataSource(URL,"sa","").locations("classpath:db/migration/common").target("26").load();flyway.migrate();
        try(Connection c=DriverManager.getConnection(URL,"sa","")){
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("26");
            JsonNode unique=document(c,"unique");assertThat(unique.path("linkedVideoTimelineItemId").asText()).isNotBlank();assertThat(unique.path("timelineRelinkRequired").asBoolean()).isFalse();
            assertThat(document(c,"ambiguous").path("timelineRelinkReason").asText()).isEqualTo("AMBIGUOUS_REPEATED_SHOT");
            for(String marker:new String[]{"missing","ambience","global-sfx"}){JsonNode global=document(c,marker);assertThat(global.has("timelineRelinkRequired")).isFalse();assertThat(global.has("timelineRelinkReason")).isFalse();}
            assertThat(document(c,"dialogue-missing").path("timelineRelinkReason").asText()).isEqualTo("VIDEO_CLIP_NOT_FOUND");
            assertThat(document(c,"existing").path("linkedVideoTimelineItemId").asText()).isEqualTo(videoB1.toString());
            assertThat(link(c,"existing")).isEqualTo(videoB1);
        }
    }

    private static void insertItem(Connection c,UUID id,UUID project,UUID timeline,UUID shot,String track,Instant now)throws Exception{String doc="{\"id\":\""+id+"\",\"projectId\":\""+project+"\",\"timelineId\":\""+timeline+"\",\"shotId\":"+(shot==null?"null":"\""+shot+"\"")+",\"track\":\""+track+"\"}";exec(c,"INSERT INTO \"timeline_item\"(id,project_id,parent_id,revision,created_at,updated_at,document,timeline_id,shot_id) VALUES (?,?,?,1,?,?,?,?,?)",id,project,timeline,now,now,doc,timeline,shot);}
    private static void insertLinkedItem(Connection c,UUID id,UUID project,UUID timeline,UUID shot,String track,UUID linked,Instant now)throws Exception{String doc="{\"id\":\""+id+"\",\"projectId\":\""+project+"\",\"timelineId\":\""+timeline+"\",\"shotId\":\""+shot+"\",\"track\":\""+track+"\",\"linkedVideoTimelineItemId\":\""+linked+"\"}";exec(c,"INSERT INTO \"timeline_item\"(id,project_id,parent_id,revision,created_at,updated_at,document,timeline_id,shot_id,linked_video_timeline_item_id) VALUES (?,?,?,1,?,?,?,?,?,?)",id,project,timeline,now,now,doc,timeline,shot,linked);}
    private static JsonNode document(Connection c,String marker)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT document FROM \"timeline_item\" WHERE id=?")){s.setObject(1,UUID.nameUUIDFromBytes(marker.getBytes()));try(ResultSet r=s.executeQuery()){r.next();return JSON.readTree(r.getString(1));}}}
    private static UUID link(Connection c,String marker)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT linked_video_timeline_item_id FROM \"timeline_item\" WHERE id=?")){s.setObject(1,UUID.nameUUIDFromBytes(marker.getBytes()));try(ResultSet r=s.executeQuery()){r.next();return r.getObject(1,UUID.class);}}}
    private static String json(UUID id,UUID project,UUID parent){return "{\"id\":\""+id+"\",\"projectId\":\""+project+"\""+(parent==null?"":",\"parentId\":\""+parent+"\"")+"}";}
    private static void exec(Connection c,String sql,Object... args)throws Exception{try(PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}}
}
