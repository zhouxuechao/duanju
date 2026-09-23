package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Creates a conservative v1 workspace version from legacy episode scripts without inventing provenance. */
public class V30__backfill_script_workspace extends BaseJavaMigration {
    private static final ObjectMapper JSON=new ObjectMapper();

    @Override public void migrate(Context context)throws Exception{
        Connection connection=context.getConnection();normalizeProjects(connection);backfillEpisodes(connection);
    }

    private void normalizeProjects(Connection connection)throws Exception{
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT id,document FROM \"project\"");PreparedStatement update=connection.prepareStatement("UPDATE \"project\" SET document=? WHERE id=?")){
            while(rows.next()){
                ObjectNode project=object(rows.getString("document"));if(!project.hasNonNull("sourceMode"))project.put("sourceMode","IDEA");
                update.setString(1,project.toString());update.setObject(2,rows.getObject("id"));update.addBatch();
            }update.executeBatch();
        }
    }

    private void backfillEpisodes(Connection connection)throws Exception{
        String versionSql="INSERT INTO \"episode_script_version\"(id,project_id,parent_id,revision,created_at,updated_at,document,episode_id,version,status,content_hash,source_mode) VALUES (?,?,?,1,?,?,?,?,1,?,?,?)";
        String snapshotSql="INSERT INTO \"production_script_snapshot\"(id,project_id,parent_id,revision,created_at,updated_at,document,episode_id,script_version_id,script_hash,continuity_snapshot_hash) VALUES (?,?,?,1,?,?,?,?,?,?,?)";
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT id,project_id,document FROM \"episode\" ORDER BY created_at,id");PreparedStatement document=connection.prepareStatement("SELECT document FROM \"story_document\" WHERE id=?");PreparedStatement insertVersion=connection.prepareStatement(versionSql);PreparedStatement insertSnapshot=connection.prepareStatement(snapshotSql);PreparedStatement updateEpisode=connection.prepareStatement("UPDATE \"episode\" SET document=? WHERE id=?")){
            while(rows.next()){
                UUID episodeId=(UUID)rows.getObject("id"),projectId=(UUID)rows.getObject("project_id");ObjectNode episode=object(rows.getString("document"));UUID legacyId=uuid(episode.path("storyDocumentId").asText());if(legacyId==null)continue;
                document.setObject(1,legacyId);try(ResultSet legacyRows=document.executeQuery()){
                    if(!legacyRows.next())continue;ObjectNode legacy=object(legacyRows.getString(1));if(!"EPISODE_SCRIPT".equals(legacy.path("documentType").asText()))continue;
                    JsonNode content=legacy.path("content");String status="CONFIRMED".equals(legacy.path("reviewStatus").asText())?"CONFIRMED":"UNKNOWN",sourceMode="IDEA",contentHash=hash(content),now=Instant.now().toString();UUID versionId=UUID.randomUUID();
                    ObjectNode version=JSON.createObjectNode().put("id",versionId.toString()).put("projectId",projectId.toString()).put("parentId",episodeId.toString()).put("episodeId",episodeId.toString()).put("version",1).put("status",status).put("createdBy","SYSTEM").put("changeType","MIGRATED_BASELINE").put("changeReason","MIGRATED_BASELINE").put("changeSummary","从旧 EPISODE_SCRIPT 建立基线").put("ownership","AI_GENERATED").put("contentHash",contentHash).put("sourceMode",sourceMode).put("legacyStoryDocumentId",legacyId.toString()).put("revision",1).put("createdAt",now).put("updatedAt",now);
                    version.set("structuredContent",content.deepCopy());version.put("rawText",content.path("script").asText(""));
                    if("CONFIRMED".equals(status)&&legacy.hasNonNull("confirmedAt"))version.set("confirmedAt",legacy.path("confirmedAt").deepCopy());
                    Timestamp timestamp=Timestamp.from(Instant.parse(now));
                    if("CONFIRMED".equals(status)){
                        UUID snapshotId=UUID.randomUUID();String continuity=legacy.path("continuityHash").asText("UNKNOWN");ObjectNode snapshot=JSON.createObjectNode().put("id",snapshotId.toString()).put("projectId",projectId.toString()).put("parentId",episodeId.toString()).put("episodeId",episodeId.toString()).put("scriptVersionId",versionId.toString()).put("scriptHash",contentHash).put("continuitySnapshotHash",continuity).put("revision",1).put("createdAt",now).put("updatedAt",now).put("migrationSource","MIGRATED_BASELINE");snapshot.set("entityVersionSnapshot",JSON.createObjectNode().put("status","UNKNOWN"));
                        insertSnapshot.setObject(1,snapshotId);insertSnapshot.setObject(2,projectId);insertSnapshot.setObject(3,episodeId);insertSnapshot.setTimestamp(4,timestamp);insertSnapshot.setTimestamp(5,timestamp);insertSnapshot.setString(6,snapshot.toString());insertSnapshot.setObject(7,episodeId);insertSnapshot.setObject(8,versionId);insertSnapshot.setString(9,contentHash);insertSnapshot.setString(10,continuity);insertSnapshot.addBatch();
                        version.put("productionScriptSnapshotId",snapshotId.toString());episode.put("confirmedScriptVersionId",versionId.toString()).put("scriptVersionId",versionId.toString()).put("productionScriptSnapshotId",snapshotId.toString()).put("productionReady",true);
                    }
                    insertVersion.setObject(1,versionId);insertVersion.setObject(2,projectId);insertVersion.setObject(3,episodeId);insertVersion.setTimestamp(4,timestamp);insertVersion.setTimestamp(5,timestamp);insertVersion.setString(6,version.toString());insertVersion.setObject(7,episodeId);insertVersion.setString(8,status);insertVersion.setString(9,contentHash);insertVersion.setString(10,sourceMode);insertVersion.addBatch();
                    updateEpisode.setString(1,episode.toString());updateEpisode.setObject(2,episodeId);updateEpisode.addBatch();
                }
            }insertVersion.executeBatch();insertSnapshot.executeBatch();updateEpisode.executeBatch();
        }
    }

    private static ObjectNode object(String json)throws Exception{return (ObjectNode)JSON.readTree(json);}
    private static UUID uuid(String value){try{return UUID.fromString(value);}catch(Exception ignored){return null;}}
    private static String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode result=JSON.createObjectNode();List<String> names=new ArrayList<>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)result.set(name,canonical(value.path(name)));return result;}if(value.isArray()){var result=JSON.createArrayNode();value.forEach(item->result.add(canonical(item)));return result;}return value.deepCopy();}
}
