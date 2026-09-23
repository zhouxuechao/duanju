package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Backfills only facts that can be proven from legacy rows. Unknown provenance stays UNKNOWN. */
public class V28__backfill_revision_foundation extends BaseJavaMigration {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> SYSTEM_FIELDS=Set.of("id","projectId","parentId","revision","createdAt","updatedAt","identityLocked");

    @Override public void migrate(Context context)throws Exception{
        Connection connection=context.getConnection();Map<UUID,UUID> characterVersions=new HashMap<>(),locationVersions=new HashMap<>(),propVersions=new HashMap<>();
        baselineDefinitions(connection,"character","character_definition_version","characterId",characterVersions);
        baselineDefinitions(connection,"location","location_definition_version","locationId",locationVersions);
        baselineDefinitions(connection,"prop","prop_definition_version","propId",propVersions);
        backfillLooks(connection);
        backfillKeyframes(connection,characterVersions,locationVersions,propVersions);
        backfillVideoTakes(connection);
    }

    private void baselineDefinitions(Connection connection,String sourceTable,String targetTable,String entityField,Map<UUID,UUID> versions)throws Exception{
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT id,project_id,document FROM \""+sourceTable+"\" ORDER BY created_at,id");PreparedStatement insert=connection.prepareStatement(
                "INSERT INTO \""+targetTable+"\"(id,project_id,parent_id,revision,created_at,updated_at,document,"+snake(entityField)+",version,content_hash) VALUES (?,?,?,1,?,?,?,?,1,?)")){
            while(rows.next()){
                UUID entityId=(UUID)rows.getObject("id"),projectId=(UUID)rows.getObject("project_id"),versionId=UUID.randomUUID();ObjectNode entity=object(rows.getString("document")),definition=clean(entity),document=definition.deepCopy();String hash=hash(definition),now=Instant.now().toString();
                document.put("id",versionId.toString()).put("projectId",projectId.toString()).put("parentId",entityId.toString()).put(entityField,entityId.toString()).put("version",1)
                        .put("validScope","GLOBAL").put("changeType","DEFINITION_REVISION").put("changeReason","MIGRATED_BASELINE").put("changedBy","SYSTEM").put("contentHash",hash)
                        .put("revision",1).put("createdAt",now).put("updatedAt",now);document.set("definition",definition.deepCopy());document.set("changePoint",JSON.createObjectNode());
                Timestamp timestamp=Timestamp.from(Instant.parse(now));insert.setObject(1,versionId);insert.setObject(2,projectId);insert.setObject(3,entityId);insert.setTimestamp(4,timestamp);insert.setTimestamp(5,timestamp);insert.setString(6,document.toString());insert.setObject(7,entityId);insert.setString(8,hash);insert.addBatch();versions.put(entityId,versionId);
            }insert.executeBatch();
        }
    }

    private void backfillLooks(Connection connection)throws Exception{
        Map<UUID,Integer> versions=new HashMap<>();
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT id,character_id,document FROM \"character_look\" ORDER BY character_id,created_at,id");PreparedStatement update=connection.prepareStatement("UPDATE \"character_look\" SET document=? WHERE id=?")){
            while(rows.next()){
                UUID characterId=(UUID)rows.getObject("character_id"),lookId=(UUID)rows.getObject("id");ObjectNode document=object(rows.getString("document"));int version=versions.merge(characterId,1,Integer::sum);
                if(!document.path("version").isIntegralNumber())document.put("version",version);
                document.putIfAbsent("changeType",JSON.getNodeFactory().textNode("DEFINITION_REVISION"));document.putIfAbsent("validScope",JSON.getNodeFactory().textNode("GLOBAL"));document.putIfAbsent("changeReason",JSON.getNodeFactory().textNode("MIGRATED_BASELINE"));document.putIfAbsent("changedBy",JSON.getNodeFactory().textNode("SYSTEM"));document.putIfAbsent("validFromStoryTime",JSON.getNodeFactory().numberNode(0));document.putIfAbsent("contentHash",JSON.getNodeFactory().textNode(hash(clean(document))));
                update.setString(1,document.toString());update.setObject(2,lookId);update.addBatch();
            }update.executeBatch();
        }
    }

    private void backfillKeyframes(Connection connection,Map<UUID,UUID> characterVersions,Map<UUID,UUID> locationVersions,Map<UUID,UUID> propVersions)throws Exception{
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT id,project_id,document FROM \"keyframe\" ORDER BY created_at,id");PreparedStatement insert=connection.prepareStatement("INSERT INTO \"production_input_snapshot\"(id,project_id,parent_id,revision,created_at,updated_at,document,source_kind,source_id,prompt_version_id,script_version_id,asset_snapshot_hash,snapshot_key) VALUES (?,?,?,1,?,?,?,?,?,?,?,?,?)");PreparedStatement update=connection.prepareStatement("UPDATE \"keyframe\" SET document=? WHERE id=?")){
            while(rows.next()){
                UUID sourceId=(UUID)rows.getObject("id"),projectId=(UUID)rows.getObject("project_id");ObjectNode media=object(rows.getString("document"));JsonNode input=media.path("generationInputSnapshot");if(!input.isObject()||media.hasNonNull("productionInputSnapshotId"))continue;
                ObjectNode snapshot=legacySnapshot("KEYFRAME",sourceId,input,characterVersions,locationVersions,propVersions);UUID snapshotId=UUID.randomUUID(),promptId=uuidOrNull(snapshot.path("promptVersionId").asText()),scriptId=uuidOrNull(snapshot.path("scriptVersionId").asText());String now=Instant.now().toString(),assetHash=snapshot.path("assetSnapshotHash").asText(),key=snapshot.path("snapshotKey").asText();
                snapshot.put("id",snapshotId.toString()).put("projectId",projectId.toString()).put("parentId",projectId.toString()).put("revision",1).put("createdAt",now).put("updatedAt",now);
                Timestamp timestamp=Timestamp.from(Instant.parse(now));insert.setObject(1,snapshotId);insert.setObject(2,projectId);insert.setObject(3,projectId);insert.setTimestamp(4,timestamp);insert.setTimestamp(5,timestamp);insert.setString(6,snapshot.toString());insert.setString(7,"KEYFRAME");insert.setObject(8,sourceId);insert.setObject(9,promptId);insert.setObject(10,scriptId);insert.setString(11,assetHash);insert.setString(12,key);insert.addBatch();
                media.put("productionInputSnapshotId",snapshotId.toString()).put("assetSnapshotHash",assetHash);if(promptId!=null)media.put("promptVersionId",promptId.toString());if(scriptId!=null)media.put("scriptVersionId",scriptId.toString());update.setString(1,media.toString());update.setObject(2,sourceId);update.addBatch();
            }insert.executeBatch();update.executeBatch();
        }
    }

    private void backfillVideoTakes(Connection connection)throws Exception{
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT v.id,v.document,k.document AS keyframe_document FROM \"video_take\" v LEFT JOIN \"keyframe\" k ON k.id=v.source_keyframe_id ORDER BY v.created_at,v.id");PreparedStatement update=connection.prepareStatement("UPDATE \"video_take\" SET document=? WHERE id=?")){
            while(rows.next()){
                ObjectNode video=object(rows.getString("document"));if(video.hasNonNull("productionInputSnapshotId"))continue;String keyframeJson=rows.getString("keyframe_document");if(keyframeJson==null)continue;JsonNode keyframe=JSON.readTree(keyframeJson);if(!keyframe.hasNonNull("productionInputSnapshotId"))continue;
                video.set("productionInputSnapshotId",keyframe.path("productionInputSnapshotId").deepCopy());video.set("assetSnapshotHash",keyframe.path("assetSnapshotHash").deepCopy());if(keyframe.hasNonNull("scriptVersionId"))video.set("scriptVersionId",keyframe.path("scriptVersionId").deepCopy());update.setString(1,video.toString());update.setObject(2,rows.getObject("id"));update.addBatch();
            }update.executeBatch();
        }
    }

    private ObjectNode legacySnapshot(String sourceKind,UUID sourceId,JsonNode input,Map<UUID,UUID> characterVersions,Map<UUID,UUID> locationVersions,Map<UUID,UUID> propVersions){
        ObjectNode snapshot=JSON.createObjectNode().put("sourceKind",sourceKind).put("sourceId",sourceId.toString()).put("scriptHash","UNKNOWN")
                .put("storyFactHash","UNKNOWN").put("knowledgeSnapshotHash","UNKNOWN").put("relationshipSnapshotHash","UNKNOWN").put("continuitySnapshotHash",input.path("continuitySnapshotHash").asText("UNKNOWN"));
        copyText(input,snapshot,"promptVersionId");copyText(input,snapshot,"scriptVersionId");mapVersions(snapshot.putArray("characterDefinitionVersionIds"),input.path("characterIds"),characterVersions);copyArray(snapshot,"characterLookVersionIds",input.path("characterLookIds"));mapVersions(snapshot.putArray("locationDefinitionVersionIds"),input.path("locationIds"),locationVersions);mapVersions(snapshot.putArray("propDefinitionVersionIds"),input.path("propIds"),propVersions);
        ArrayNode unknown=snapshot.putArray("unknownFields");for(String field:List.of("promptVersionId","scriptVersionId"))if(!snapshot.hasNonNull(field))unknown.add(field);String assetHash=hash(snapshot);snapshot.put("assetSnapshotHash",assetHash).put("snapshotKey",hash(sourceKind+"|"+sourceId+"|"+assetHash));return snapshot;
    }

    private static void mapVersions(ArrayNode output,JsonNode entityIds,Map<UUID,UUID> versions){if(!entityIds.isArray())return;for(JsonNode value:entityIds)try{UUID version=versions.get(UUID.fromString(value.asText()));if(version!=null)output.add(version.toString());}catch(Exception ignored){}}
    private static void copyArray(ObjectNode target,String field,JsonNode source){ArrayNode values=target.putArray(field);if(source.isArray())source.forEach(value->{if(value.isTextual())values.add(value.asText());});}
    private static void copyText(JsonNode source,ObjectNode target,String field){if(source.path(field).isTextual()&&!source.path(field).asText().isBlank())target.put(field,source.path(field).asText());}
    private static ObjectNode clean(JsonNode source){ObjectNode result=JSON.createObjectNode();source.fields().forEachRemaining(entry->{if(!SYSTEM_FIELDS.contains(entry.getKey()))result.set(entry.getKey(),entry.getValue().deepCopy());});return result;}
    private static ObjectNode object(String json)throws Exception{return (ObjectNode)JSON.readTree(json);}
    private static UUID uuidOrNull(String value){try{return UUID.fromString(value);}catch(Exception ignored){return null;}}
    private static String snake(String value){return value.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static String hash(JsonNode value){return hash(canonical(value).toString());}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode result=JSON.createObjectNode();List<String> names=new ArrayList<>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)result.set(name,canonical(value.path(name)));return result;}if(value.isArray()){ArrayNode result=JSON.createArrayNode();value.forEach(item->result.add(canonical(item)));return result;}return value.deepCopy();}
}
