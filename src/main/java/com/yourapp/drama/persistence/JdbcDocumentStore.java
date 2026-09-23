package com.yourapp.drama.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.yourapp.drama.persistence.ResourceKind.*;

/** Each document lives in its own resource table, with indexed relational safety fields. */
@Repository
public class JdbcDocumentStore implements DocumentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private record Column(String field, String type) {
        String sql() { return field.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    }
    private static Column col(String name, String type) { return new Column(name,type); }
    private static final Map<ResourceKind,List<Column>> COLUMNS = columns();
    private static Map<ResourceKind,List<Column>> columns() {
        var m = new EnumMap<ResourceKind,List<Column>>(ResourceKind.class);
        for (var kind : ResourceKind.values()) m.put(kind, new ArrayList<>());
        for (var kind : ResourceKind.values()) if(kind.parentField()!=null) m.get(kind).add(col(kind.parentField(),"uuid"));
        m.get(SHOT).addAll(List.of(col("duration","decimal"),col("status","text")));
        m.get(CHARACTER).addAll(List.of(col("provider","text"),col("sourceType","text"),col("providerAssetId","text"),col("providerStatus","text"),col("identityLocked","bool")));
        for(ResourceKind kind:List.of(CHARACTER_DEFINITION_VERSION,LOCATION_DEFINITION_VERSION,PROP_DEFINITION_VERSION))m.get(kind).addAll(List.of(col("version","int"),col("supersedesId","uuid"),col("effectiveFromEpisode","int"),col("effectiveFromScene","int"),col("effectiveFromStoryTime","decimal"),col("contentHash","text")));
        m.get(CHARACTER_STATE).addAll(List.of(col("characterId","uuid"),col("state","text"),col("validFromStoryTime","decimal"),col("validToStoryTime","decimal"),col("sourceSceneId","uuid"),col("sourceShotId","uuid")));
        m.get(CHARACTER_PROVIDER_ASSET).addAll(List.of(col("provider","text"),col("providerAssetId","text"),col("providerStatus","text")));
        m.get(KEYFRAME).addAll(List.of(col("version","int"),col("provider","text"),col("sourceModel","text"),col("providerUrl","text"),col("providerUrlExpiresAt","time"),col("archiveUrl","text"),col("generationJobId","uuid"),col("providerRequestId","text"),col("handoffStatus","text"),col("qcStatus","text"),col("selected","bool"),col("locked","bool")));
        m.get(VIDEO_TAKE).addAll(List.of(col("takeNo","int"),col("provider","text"),col("model","text"),col("promptVersionId","uuid"),col("providerRequestId","text"),col("sourceKeyframeId","uuid"),col("sourceProviderUrlSnapshot","text"),col("videoUrl","text"),col("archiveUrl","text"),col("qcScore","decimal"),col("selected","bool"),col("locked","bool"),col("cost","decimal")));
        m.get(GENERATION_JOB).addAll(List.of(col("shotId","uuid"),col("type","text"),col("status","text"),col("providerRequestId","text"),col("providerTaskId","text"),col("promptVersionId","uuid"),col("requestKey","text"),col("retryAt","time"),col("attempts","int"),col("maxAttempts","int"),col("progress","decimal"),col("cost","decimal"),col("failureReason","text"),col("cancelRequested","bool")));
        m.get(STORY_FACT).addAll(List.of(col("factKey","text"),col("predicate","text"),col("validFromStoryTime","decimal"),col("validToStoryTime","decimal"),col("revealedAtStoryTime","decimal"),col("status","text"),col("sourceSceneId","uuid"),col("sourceShotId","uuid")));
        m.get(STORY_FACT_MUTATION).addAll(List.of(col("factId","uuid"),col("operation","text"),col("effectiveFromStoryTime","decimal"),col("sceneId","uuid"),col("beatId","uuid")));
        m.get(CHARACTER_KNOWLEDGE).addAll(List.of(col("characterId","uuid"),col("factId","uuid"),col("knowledgeState","text"),col("knownFromStoryTime","decimal"),col("validFromStoryTime","decimal"),col("validToStoryTime","decimal"),col("believedStatement","text"),col("knownFromSceneId","uuid")));
        m.get(ENTITY_ALIAS).addAll(List.of(col("entityId","uuid"),col("alias","text"),col("aliasType","text"),col("validFromStoryTime","decimal"),col("validToStoryTime","decimal"),col("source","text"),col("confidence","decimal")));
        m.get(RELATIONSHIP).addAll(List.of(col("subjectCharacterId","uuid"),col("objectCharacterId","uuid"),col("relationshipType","text"),col("state","text"),col("validFromStoryTime","decimal"),col("validToStoryTime","decimal"),col("sourceSceneId","uuid"),col("sourceShotId","uuid")));
        m.get(LOCATION_STATE).addAll(List.of(col("locationId","uuid"),col("state","text"),col("validFromStoryTime","decimal"),col("validToStoryTime","decimal"),col("sourceSceneId","uuid"),col("sourceShotId","uuid")));
        m.get(DEPENDENCY_EDGE).addAll(List.of(col("sourceKind","text"),col("sourceId","uuid"),col("targetKind","text"),col("targetId","uuid"),col("dependencyType","text"),col("episodeNo","int"),col("sceneNo","int"),col("storyTime","decimal")));
        m.get(REVALIDATION_MARKER).addAll(List.of(col("resourceKind","text"),col("resourceId","uuid"),col("sourceVersionId","uuid"),col("status","text")));
        m.get(PRODUCTION_INPUT_SNAPSHOT).addAll(List.of(col("sourceKind","text"),col("sourceId","uuid"),col("promptVersionId","uuid"),col("scriptVersionId","uuid"),col("assetSnapshotHash","text"),col("snapshotKey","text")));
        m.get(EPISODE_SCRIPT_VERSION).addAll(List.of(col("version","int"),col("supersedesId","uuid"),col("sourceVersionId","uuid"),col("status","text"),col("contentHash","text"),col("sourceMode","text"),col("adaptationPlanId","uuid"),col("episodeAdaptationPlanId","uuid"),col("contextHash","text")));
        m.get(PRODUCTION_SCRIPT_SNAPSHOT).addAll(List.of(col("scriptVersionId","uuid"),col("scriptHash","text"),col("continuitySnapshotHash","text")));
        m.get(IMPACT_PLAN).addAll(List.of(col("sourceVersionId","uuid"),col("targetVersionId","uuid"),col("impactType","text"),col("changeScope","text")));
        m.get(REBUILD_PLAN).addAll(List.of(col("impactPlanId","uuid"),col("status","text"),col("requiresUserConfirmation","bool")));
        m.get(PLATFORM_REVIEW_ISSUE).addAll(List.of(col("scriptVersionId","uuid"),col("platform","text"),col("reasonCode","text"),col("status","text"),col("resolutionVersionId","uuid")));
        m.get(NOVEL_UPLOAD_SESSION).addAll(List.of(col("uploadId","uuid"),col("status","text"),col("fileSha256","text"),col("storageKey","text")));
        m.get(NOVEL_SOURCE).addAll(List.of(col("format","text"),col("fileHash","text"),col("status","text"),col("storageKey","text")));
        m.get(NOVEL_CHAPTER).addAll(List.of(col("chapterNo","int"),col("contentHash","text"),col("sourceStart","int"),col("sourceEnd","int")));
        m.get(NOVEL_CHUNK).addAll(List.of(col("novelId","uuid"),col("chunkNo","int"),col("contentHash","text"),col("sourceStart","int"),col("sourceEnd","int")));
        m.get(SOURCE_REFERENCE).addAll(List.of(col("novelId","uuid"),col("sourceType","text"),col("startOffset","int"),col("endOffset","int"),col("contentHash","text")));
        m.get(NOVEL_CHUNK_ANALYSIS).addAll(List.of(col("novelId","uuid"),col("contentHash","text"),col("analysisVersion","int"),col("profile","text")));
        m.get(NOVEL_CHAPTER_ANALYSIS).addAll(List.of(col("novelId","uuid"),col("analysisRevision","int"),col("profile","text")));
        m.get(ENTITY_CANDIDATE).addAll(List.of(col("novelId","uuid"),col("entityKind","text"),col("canonicalName","text"),col("status","text"),col("confidence","decimal")));
        m.get(NOVEL_STORY_ARC).addAll(List.of(col("startChapter","int"),col("endChapter","int"),col("analysisRevision","int")));
        m.get(NOVEL_STORY_GRAPH).addAll(List.of(col("analysisRevision","int"),col("profile","text")));
        m.get(NOVEL_ANALYSIS_JOB).addAll(List.of(col("novelId","uuid"),col("chapterId","uuid"),col("status","text"),col("idempotencyKey","text"),col("contentHash","text"),col("profile","text"),col("analysisVersion","int")));
        m.get(NOVEL_AI_JOB).addAll(List.of(col("novelId","uuid"),col("taskType","text"),col("status","text"),col("providerRequestId","text"),col("model","text"),col("contextHash","text"),col("compilerVersion","text")));
        m.get(ADAPTATION_PLAN).addAll(List.of(col("novelId","uuid"),col("analysisRevision","int"),col("version","int"),col("status","text"),col("adaptationStyle","text")));
        m.get(EPISODE_ADAPTATION_PLAN).addAll(List.of(col("episodeNo","int"),col("status","text")));
        m.get(PROMPT_VERSION).addAll(List.of(col("shotId","uuid"),col("version","int"),col("promptTemplateId","uuid")));
        m.get(STORYBOARD).add(col("version","int"));
        m.get(DIALOGUE_LINE).addAll(List.of(col("characterId","uuid"),col("semanticText","text"),col("spokenText","text"),col("subtitleText","text")));
        m.get(VOICE_PROFILE).add(col("characterId","uuid"));
        m.get(VOICE_STATE).addAll(List.of(col("voiceProfileId","uuid"),col("state","text"),col("providerVoiceId","text"),col("referenceAudioUrl","text"),col("validFromStoryTime","decimal"),col("validToStoryTime","decimal")));
        m.get(AUDIO_CLIP).addAll(List.of(col("shotId","uuid"),col("dialogueLineId","uuid")));
        m.get(QC_RESULT).addAll(List.of(col("shotId","uuid"),col("keyframeId","uuid"),col("videoTakeId","uuid"),col("generationJobId","uuid")));
        m.get(COST_RECORD).addAll(List.of(col("generationJobId","uuid"),col("cost","decimal")));
        m.get(TIMELINE_ITEM).addAll(List.of(col("shotId","uuid"),col("videoTakeId","uuid"),col("audioClipId","uuid"),col("linkedVideoTimelineItemId","uuid")));
        return m;
    }
    public JdbcDocumentStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.mapper=mapper; this.transactions=new TransactionTemplate(manager);
    }
    @Override public <T>T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    @Override public ObjectNode get(ResourceKind kind,String id) { return find(kind,id).orElseThrow(() -> new ResourceNotFoundException(kind,id)); }
    @Override public Optional<ObjectNode> find(ResourceKind kind,String id) {
        return jdbc.query("SELECT document FROM " + table(kind) + " WHERE id = ?", (rs,n)->read(rs.getString(1)),uuid(id)).stream().findFirst();
    }
    @Override public ObjectNode getForUpdate(ResourceKind kind,String id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("getForUpdate requires transaction()");
        return jdbc.query("SELECT document FROM "+table(kind)+" WHERE id = ? FOR UPDATE",(rs,n)->read(rs.getString(1)),uuid(id))
            .stream().findFirst().orElseThrow(()->new ResourceNotFoundException(kind,id));
    }
    @Override public List<ObjectNode> list(ResourceKind kind,String projectId,String parentId) {
        var sql=new StringBuilder("SELECT document FROM ").append(table(kind)).append(" WHERE 1=1");
        var args=new ArrayList<Object>();
        if(projectId!=null) { sql.append(" AND project_id = ?"); args.add(uuid(projectId)); }
        if(parentId!=null) { sql.append(" AND parent_id = ?"); args.add(uuid(parentId)); }
        sql.append(" ORDER BY created_at, id");
        List<ObjectNode> result=jdbc.query(sql.toString(),(rs,n)->read(rs.getString(1)),args.toArray());
        Comparator<ObjectNode> domainOrder=switch(kind){
            case NOVEL_CHAPTER -> Comparator.comparingDouble(value->value.path("displayOrder").asDouble(value.path("chapterNo").asDouble()));
            case NOVEL_CHUNK -> Comparator.comparingInt(value->value.path("chunkNo").asInt());
            case EPISODE_ADAPTATION_PLAN -> Comparator.comparingInt(value->value.path("episodeNo").asInt());
            case CHARACTER_STATE,LOCATION_STATE,PROP_STATE,VOICE_STATE -> Comparator.comparingDouble(value->value.path("validFromStoryTime").asDouble());
            default -> null;
        };
        if(domainOrder!=null)result.sort(domainOrder.thenComparing(value->value.path("id").asText()));
        return result;
    }
    @Override public ObjectNode create(ResourceKind kind,ObjectNode input) {
        return transaction(()->{
            var doc=input.deepCopy();
            String id=doc.path("id").asText(UUID.randomUUID().toString()); uuid(id);
            doc.put("id",id); defaults(kind,doc);
            prepareReferences(kind,doc);
            String now=Instant.now().toString(); doc.put("revision",1L).put("createdAt",now).put("updatedAt",now);
            if(kind==KEYFRAME || kind==STORYBOARD || kind==PROMPT_VERSION || kind==VIDEO_TAKE) {
                String versionField=kind==VIDEO_TAKE ? "takeNo" : "version";
                getForUpdate(PROJECT,doc.path("projectId").asText());
                if(!doc.hasNonNull(versionField)) {
                    Integer version=jdbc.queryForObject("SELECT COALESCE(MAX("+snake(versionField)+"),0)+1 FROM "+table(kind)+" WHERE project_id=? AND parent_id=?",Integer.class,uuid(doc.path("projectId").asText()),uuid(doc.path("parentId").asText()));
                    doc.put(versionField,version==null ? 1 : version);
                }
            }
            validateMedia(kind,doc,null);
            var row=row(kind,doc);
            jdbc.update("INSERT INTO "+table(kind)+" ("+String.join(",",row.keySet())+") VALUES ("+row.keySet().stream().map(k->"?").collect(Collectors.joining(","))+")",row.values().toArray());
            return doc.deepCopy();
        });
    }
    @Override public ObjectNode update(ResourceKind kind,String id,long expectedRevision,ObjectNode input) {
        return transaction(()->{
            if(kind.immutable()) throw new IllegalArgumentException(kind.path()+" are immutable; create a new version");
            ObjectNode previous=getForUpdate(kind,id);
            if(previous.path("revision").asLong()!=expectedRevision) throw new RevisionConflictException(kind,id);
            ObjectNode doc=input.deepCopy();
            for(String field:List.of("id","projectId","parentId","createdAt")) {
                if(doc.hasNonNull(field) && !doc.get(field).equals(previous.get(field))) throw new IllegalArgumentException(field+" cannot change");
                if(previous.has(field)) doc.set(field,previous.get(field));
            }
            if(kind.parentField()!=null && !Objects.equals(doc.get(kind.parentField()),previous.get(kind.parentField()))) throw new IllegalArgumentException(kind.parentField()+" cannot change");
            defaults(kind,doc); prepareReferences(kind,doc); validateMedia(kind,doc,previous);
            doc.put("revision",expectedRevision+1).put("updatedAt",Instant.now().toString());
            var row=row(kind,doc); row.remove("id");
            var args=new ArrayList<>(row.values()); args.add(uuid(id)); args.add(expectedRevision);
            int changed=jdbc.update("UPDATE "+table(kind)+" SET "+row.keySet().stream().map(k->k+"=?").collect(Collectors.joining(","))+" WHERE id=? AND revision=?",args.toArray());
            if(changed!=1) throw new RevisionConflictException(kind,id);
            return doc.deepCopy();
        });
    }
    @Override public ObjectNode closeCharacterKnowledgeInterval(String id,long expectedRevision,double validToStoryTime) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("closeCharacterKnowledgeInterval requires transaction()");
        ObjectNode current=getForUpdate(CHARACTER_KNOWLEDGE,id);
        if(current.path("revision").asLong()!=expectedRevision)throw new RevisionConflictException(CHARACTER_KNOWLEDGE,id);
        double from=current.path("validFromStoryTime").isNumber()?current.path("validFromStoryTime").asDouble():current.path("knownFromStoryTime").asDouble();
        if(current.path("validToStoryTime").isNumber()||validToStoryTime<=from)throw new IllegalArgumentException("只能关闭仍然开放且结束时间晚于开始时间的角色知识区间");
        ObjectNode closed=current.deepCopy().put("validToStoryTime",validToStoryTime).put("revision",expectedRevision+1).put("updatedAt",Instant.now().toString());
        int changed=jdbc.update("UPDATE "+table(CHARACTER_KNOWLEDGE)+" SET revision=?,updated_at=?,document=?,valid_to_story_time=? WHERE id=? AND revision=? AND valid_to_story_time IS NULL",
                closed.path("revision").asLong(),timestamp(closed.path("updatedAt").asText()),closed.toString(),BigDecimal.valueOf(validToStoryTime),uuid(id),expectedRevision);
        if(changed!=1)throw new RevisionConflictException(CHARACTER_KNOWLEDGE,id);
        return closed.deepCopy();
    }
    private void defaults(ResourceKind kind,ObjectNode doc) {
        if(kind==SHOT) { putDefault(doc,"duration",3); putDefault(doc,"status","DRAFT"); }
        if(kind==CHARACTER_KNOWLEDGE) {
            if(!doc.path("validFromStoryTime").isNumber()&&doc.path("knownFromStoryTime").isNumber())doc.set("validFromStoryTime",doc.path("knownFromStoryTime").deepCopy());
            if(!doc.path("knownFromStoryTime").isNumber()&&doc.path("validFromStoryTime").isNumber())doc.set("knownFromStoryTime",doc.path("validFromStoryTime").deepCopy());
        }
        if(kind==KEYFRAME) { putDefault(doc,"handoffStatus","READY"); putDefault(doc,"qcStatus","PENDING"); }
        if(kind==KEYFRAME || kind==VIDEO_TAKE) { putDefault(doc,"selected",false); putDefault(doc,"locked",false); }
        if(kind==CHARACTER) putDefault(doc,"identityLocked",false);
        if(kind==GENERATION_JOB) {
            putDefault(doc,"status","QUEUED"); putDefault(doc,"attempts",0); putDefault(doc,"maxAttempts",3); putDefault(doc,"progress",0); putDefault(doc,"cost",0); putDefault(doc,"cancelRequested",false);
            if(!doc.hasNonNull("type")) throw new IllegalArgumentException("Job type is required");
        }
        if(kind==VIDEO_TAKE || kind==COST_RECORD) putDefault(doc,"cost",0);
    }
    private void putDefault(ObjectNode doc,String field,Object value) { if(!doc.hasNonNull(field)) doc.set(field,mapper.valueToTree(value)); }
    private void prepareReferences(ResourceKind kind,ObjectNode doc) {
        if(kind==PROJECT) { doc.put("projectId",doc.path("id").asText()); doc.remove("parentId"); return; }
        if(kind.parentKind()!=null) {
            String parentId=required(doc,kind.parentField());
            var parent=get(kind.parentKind(),parentId);
            if(!doc.hasNonNull("projectId")) doc.put("projectId",parent.path("projectId").asText());
            if(!parent.path("projectId").asText().equals(doc.path("projectId").asText())) throw new IllegalArgumentException("Parent belongs to another project");
            if(doc.hasNonNull("parentId") && !doc.path("parentId").asText().equals(parentId)) throw new IllegalArgumentException("parentId must match "+kind.parentField());
            doc.put("parentId",parentId);
        }
        String projectId=required(doc,"projectId"); get(PROJECT,projectId);
        if(!doc.hasNonNull("parentId")) doc.put("parentId",doc.hasNonNull("shotId") && kind!=SHOT && !doc.path("shotId").asText().isBlank() ? doc.path("shotId").asText() : projectId);
        if(kind==TIMELINE_ITEM&&doc.hasNonNull("linkedVideoTimelineItemId")){
            String linkedId=doc.path("linkedVideoTimelineItemId").asText();
            if(linkedId.isBlank()){doc.remove("linkedVideoTimelineItemId");}
            else{
                if(linkedId.equals(doc.path("id").asText()))throw new IllegalArgumentException("时间线片段不能绑定自身");
                ObjectNode linked=get(TIMELINE_ITEM,linkedId);
                if(!projectId.equals(linked.path("projectId").asText()))throw new IllegalArgumentException("linkedVideoTimelineItemId belongs to another project");
            }
        }
        if(kind==ENTITY_ALIAS&&doc.hasNonNull("entityId")){
            ResourceKind target=switch(doc.path("entityKind").asText("CHARACTER")){case "LOCATION"->LOCATION;case "PROP"->PROP;default->CHARACTER;};
            ObjectNode referenced=get(target,doc.path("entityId").asText());
            if(!projectId.equals(referenced.path("projectId").asText()))throw new IllegalArgumentException("entityId belongs to another project");
        }
        var references=Map.ofEntries(Map.entry("episodeId",EPISODE),Map.entry("sceneId",SCENE),Map.entry("shotId",SHOT),Map.entry("characterId",CHARACTER),Map.entry("entityId",CHARACTER),Map.entry("voiceProfileId",VOICE_PROFILE),Map.entry("locationId",LOCATION),Map.entry("propId",PROP),Map.entry("sourceKeyframeId",KEYFRAME),Map.entry("keyframeId",KEYFRAME),Map.entry("videoTakeId",VIDEO_TAKE),Map.entry("audioClipId",AUDIO_CLIP),Map.entry("dialogueLineId",DIALOGUE_LINE),Map.entry("generationJobId",GENERATION_JOB),Map.entry("promptVersionId",PROMPT_VERSION),Map.entry("promptTemplateId",PROMPT_TEMPLATE),Map.entry("timelineId",TIMELINE),Map.entry("factId",STORY_FACT),Map.entry("sourceSceneId",SCENE),Map.entry("sourceShotId",SHOT),Map.entry("sourceBeatId",BEAT),Map.entry("knownFromSceneId",SCENE),Map.entry("subjectCharacterId",CHARACTER),Map.entry("objectCharacterId",CHARACTER));
        references.forEach((field,target)->{
            if(doc.hasNonNull(field) && target!=kind && !(kind==ENTITY_ALIAS&&field.equals("entityId"))) {
                if(doc.get(field).asText().isBlank()) { doc.remove(field); return; }
                ObjectNode referenced=get(target,doc.get(field).asText());
                if(!projectId.equals(referenced.path("projectId").asText())) throw new IllegalArgumentException(field+" belongs to another project");
            }
        });
        for(var entry:Map.of("characterIds",CHARACTER,"propIds",PROP,"dialogueIds",DIALOGUE_LINE).entrySet()) if(doc.has(entry.getKey())) {
            if(!doc.get(entry.getKey()).isArray()) throw new IllegalArgumentException(entry.getKey()+" must be an array");
            for(var reference:doc.get(entry.getKey())) if(!projectId.equals(get(entry.getValue(),reference.asText()).path("projectId").asText())) throw new IllegalArgumentException(entry.getKey()+" belongs to another project");
        }
    }
    private void validateMedia(ResourceKind kind,ObjectNode doc,ObjectNode old) {
        if(kind==KEYFRAME) {
            required(doc,"providerUrl"); required(doc,"provider");
            boolean addingArchive=doc.hasNonNull("archiveUrl") && (old==null || !Objects.equals(doc.get("archiveUrl"),old.get("archiveUrl")));
            if(addingArchive && !doc.path("handoffStatus").asText().equals("HANDED_OFF")) throw new IllegalArgumentException("Keyframe archive is allowed only after successful provider handoff");
            if(old!=null) unchanged(old,doc,"providerUrl","provider","sourceModel","providerRequestId","providerUrlExpiresAt","generationJobId","version");
        }
        if(kind==VIDEO_TAKE) {
            String keyframeId=required(doc,"sourceKeyframeId");
            String snapshot=required(doc,"sourceProviderUrlSnapshot");
            var keyframe=get(KEYFRAME,keyframeId);
            if(!snapshot.equals(keyframe.path("providerUrl").asText())) throw new IllegalArgumentException("Video take must snapshot the original providerUrl exactly");
            if(!doc.path("shotId").equals(keyframe.path("shotId"))) throw new IllegalArgumentException("Source keyframe belongs to another shot");
            if(old!=null) unchanged(old,doc,"sourceKeyframeId","sourceProviderUrlSnapshot","takeNo","providerRequestId","promptVersionId");
        }
        if(kind==CHARACTER && old!=null && old.path("identityLocked").asBoolean()) unchanged(old,doc,"provider","sourceType","providerAssetId","identityLocked");
        if(kind==GENERATION_JOB && old!=null) unchanged(old,doc,"type","inputSnapshot","promptVersionId","requestKey");
        if(kind==STORY_DOCUMENT && old!=null && "CONFIRMED".equals(old.path("reviewStatus").asText()))
            unchanged(old,doc,"content","continuitySnapshot","continuityHash","documentType","coreId","version","reviewStatus");
    }
    private void unchanged(ObjectNode old,ObjectNode doc,String... fields) { for(String field:fields) if(!Objects.equals(old.get(field),doc.get(field))) throw new IllegalArgumentException(field+" is immutable; create a new version"); }
    private LinkedHashMap<String,Object> row(ResourceKind kind,ObjectNode doc) {
        var row=new LinkedHashMap<String,Object>();
        row.put("id",uuid(doc.path("id").asText())); row.put("project_id",uuid(doc.path("projectId").asText())); row.put("parent_id",doc.hasNonNull("parentId") ? uuid(doc.path("parentId").asText()) : null);
        row.put("revision",doc.path("revision").asLong()); row.put("created_at",timestamp(doc.path("createdAt").asText())); row.put("updated_at",timestamp(doc.path("updatedAt").asText())); row.put("document",doc.toString());
        for(Column c:COLUMNS.get(kind)) {
            var node=doc.get(c.field()); Object value=null;
            if(node!=null && !node.isNull()) value=switch(c.type()) {
                case "uuid" -> uuid(node.asText()); case "int" -> node.intValue(); case "decimal" -> new BigDecimal(node.asText());
                case "bool" -> node.booleanValue(); case "time" -> timestamp(node.asText()); default -> node.asText();
            };
            row.put(c.sql(),value);
        }
        return row;
    }
    private static String required(ObjectNode doc,String field) { if(!doc.hasNonNull(field)||doc.path(field).asText().isBlank()) throw new IllegalArgumentException(field+" is required"); return doc.path(field).asText(); }
    private static String snake(String field) { return field.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    private static String table(ResourceKind kind) { return "\""+kind.table()+"\""; }
    private static UUID uuid(String value) { try { UUID id=UUID.fromString(value); if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; } catch(Exception e) { throw new IllegalArgumentException("Invalid UUID: "+value); } }
    private static OffsetDateTime timestamp(String value) { return Instant.parse(value).atOffset(ZoneOffset.UTC); }
    private ObjectNode read(String json) { try { return (ObjectNode)mapper.readTree(json); } catch(JsonProcessingException e) { throw new IllegalStateException("Stored document is invalid JSON",e); } }
}
