package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class NovelAdaptationService {
    private static final Set<String> STYLES=Set.of("FAITHFUL","BALANCED","SHORT_DRAMA");
    private final DocumentStore store;private final NovelAdaptationPlanner planner;private final NovelIntelligenceQualityValidator quality;
    public NovelAdaptationService(DocumentStore store,NovelAdaptationPlanner planner,NovelIntelligenceQualityValidator quality){this.store=store;this.planner=planner;this.quality=quality;}

    public ObjectNode create(String novelId,ObjectNode request){
        ObjectNode source=store.get(NOVEL_SOURCE,novelId);String projectId=project(source);
        if(source.path("analysisRevision").asInt()<1||store.list(NOVEL_STORY_GRAPH,projectId,novelId).isEmpty())throw new WorkflowException("NOVEL_ANALYSIS_REQUIRED","必须先完成小说分析");
        String style=request.path("adaptationStyle").asText("BALANCED").toUpperCase(Locale.ROOT);if(!STYLES.contains(style))throw new IllegalArgumentException("adaptationStyle 只支持 FAITHFUL、BALANCED、SHORT_DRAMA");
        boolean manual=request.path("episodes").isArray()&&!request.path("episodes").isEmpty();ObjectNode generated=null;
        if(!manual){String planningKey=planner.contextHash(source,request);ObjectNode existing=store.list(ADAPTATION_PLAN,projectId,null).stream().filter(p->novelId.equals(text(p,"novelId"))&&planningKey.equals(text(p,"planningContextHash"))&&!"SUPERSEDED".equals(text(p,"status"))).findFirst().orElse(null);if(existing!=null)return existing;generated=planner.plan(source,request);}
        List<ObjectNode> requested=manual?draftEpisodes(source,request):objects(generated.path("episodes"));int target=request.path("targetEpisodeCount").asInt(requested.size());if(target<1||target!=requested.size())throw new IllegalArgumentException("targetEpisodeCount 必须与分集方案数量一致");List<ObjectNode> prepared=requested.stream().map(episode->normalizeEpisode(source,episode)).toList();quality.adaptationPlan(prepared,source);
        int version=store.list(ADAPTATION_PLAN,projectId,null).stream().filter(p->novelId.equals(text(p,"novelId"))).mapToInt(p->p.path("version").asInt()).max().orElse(0)+1;
        ObjectNode value=obj().put("projectId",projectId).put("novelId",novelId).put("analysisRevision",source.path("analysisRevision").asInt()).put("version",version).put("status","REVIEW")
            .put("targetEpisodeCount",target).put("targetEpisodeDuration",request.path("targetEpisodeDuration").asInt(90)).put("adaptationStyle",style)
            .put("genre",text(request,"genre")).put("platform",text(request,"platform")).put("region",text(request,"region")).put("compressionLevel",request.path("compressionLevel").asText("MEDIUM"))
            .put("mainPlotPriority",request.path("mainPlotPriority").asText("HIGH")).put("subplotPolicy",request.path("subplotPolicy").asText("COMPRESS"))
            .put("characterMergePolicy",request.path("characterMergePolicy").asText("REVIEW_REQUIRED")).put("createdBy",request.path("createdBy").asText("USER"));
        if(request.has("contentRating"))value.set("contentRating",request.path("contentRating").deepCopy());if(request.has("sourceGapExplanation"))value.set("sourceGapExplanation",request.path("sourceGapExplanation").deepCopy());
        if(generated!=null)for(String field:List.of("planningContextHash","model","compilerVersion","seasonSkeletonRequestId","seasonSkeleton","batchRequestIds"))if(generated.has(field))value.set(field,generated.path(field).deepCopy());
        value.set("coverage",coverage(source,prepared,text(value,"sourceGapExplanation")));value.set("continuityIssues",continuityIssues(prepared));ObjectNode plan=store.create(ADAPTATION_PLAN,value);
        for(ObjectNode episode:prepared)saveEpisode(plan,source,episode);return store.get(ADAPTATION_PLAN,id(plan));
    }

    public List<ObjectNode> plans(String novelId){ObjectNode source=store.get(NOVEL_SOURCE,novelId);return store.list(ADAPTATION_PLAN,project(source),null).stream().filter(p->novelId.equals(text(p,"novelId"))).sorted(Comparator.comparingInt(p->p.path("version").asInt())).toList();}
    public List<ObjectNode> episodes(String planId){ObjectNode plan=store.get(ADAPTATION_PLAN,planId);return store.list(EPISODE_ADAPTATION_PLAN,project(plan),planId).stream().sorted(Comparator.comparingInt(e->e.path("episodeNo").asInt())).toList();}

    public ObjectNode editEpisode(String planId,int episodeNo,ObjectNode request){
        return store.transaction(()->{
            ObjectNode old=store.getForUpdate(ADAPTATION_PLAN,planId);requireRevision(old,request);if(!Set.of("DRAFT","REVIEW").contains(text(old,"status")))throw new WorkflowException("ADAPTATION_PLAN_IMMUTABLE","只有草稿或待审查计划可以编辑");
            List<ObjectNode> oldEpisodes=episodes(planId);ObjectNode selected=oldEpisodes.stream().filter(e->e.path("episodeNo").asInt()==episodeNo).findFirst().orElseThrow(()->new IllegalArgumentException("分集不存在："+episodeNo));
            if(request.path("episodePlanRevision").asLong(-1)!=revision(selected))throw new IllegalArgumentException("修改必须包含当前 episodePlanRevision");
            ObjectNode edited=selected.deepCopy();for(String field:List.of("id","parentId","planId","projectId","revision","createdAt","updatedAt"))edited.remove(field);request.fields().forEachRemaining(entry->{if(!Set.of("revision","episodePlanRevision","episodeNo").contains(entry.getKey()))edited.set(entry.getKey(),entry.getValue().deepCopy());});edited.put("episodeNo",episodeNo);
            ObjectNode source=store.get(NOVEL_SOURCE,required(old,"novelId"));ObjectNode changed=normalizeEpisode(source,edited);
            List<ObjectNode> nextEpisodes=new ArrayList<>();for(ObjectNode current:oldEpisodes)nextEpisodes.add(current.path("episodeNo").asInt()==episodeNo?changed:copyEpisode(current));
            ObjectNode next=copyPlan(old).put("version",old.path("version").asInt()+1).put("status","REVIEW").put("supersedesId",id(old)).put("createdBy",request.path("createdBy").asText("USER")).put("updatedReason","EPISODE_EDIT");
            next.set("coverage",coverage(source,nextEpisodes,text(next,"sourceGapExplanation")));next.set("continuityIssues",continuityIssues(nextEpisodes));next.putObject("diff").putArray("changedEpisodes").add(episodeNo);ArrayNode revalidated=next.putArray("revalidatedEpisodes");for(int n=Math.max(1,episodeNo-1);n<=Math.min(nextEpisodes.size(),episodeNo+1);n++)revalidated.add(n);
            ObjectNode saved=store.create(ADAPTATION_PLAN,next);for(ObjectNode episode:nextEpisodes)saveEpisode(saved,source,episode);
            store.update(ADAPTATION_PLAN,id(old),revision(old),old.deepCopy().put("status","SUPERSEDED").put("supersededById",id(saved)));
            return saved;
        });
    }

    public ObjectNode confirm(String planId,ObjectNode request){
        return store.transaction(()->{ObjectNode plan=store.getForUpdate(ADAPTATION_PLAN,planId);requireRevision(plan,request);if(!Set.of("DRAFT","REVIEW").contains(text(plan,"status")))throw new WorkflowException("ADAPTATION_PLAN_NOT_REVIEWABLE","当前计划不能确认");if(plan.path("coverage").path("uncoveredChapterIds").size()>0&&text(plan,"sourceGapExplanation").isBlank())throw new WorkflowException("ADAPTATION_COVERAGE_INCOMPLETE","未覆盖章节必须填写原因");if(!plan.path("continuityIssues").isEmpty())throw new WorkflowException("ADAPTATION_CONTINUITY_INVALID","相邻分集的知识、道具或地点状态不连续");return store.update(ADAPTATION_PLAN,planId,revision(plan),plan.deepCopy().put("status","CONFIRMED").put("confirmedAt",Instant.now().toString()));});
    }
    public ObjectNode requireConfirmed(String planId){ObjectNode plan=store.get(ADAPTATION_PLAN,planId);if(!"CONFIRMED".equals(text(plan,"status")))throw new WorkflowException("ADAPTATION_PLAN_NOT_CONFIRMED","只有已确认的改编计划才能生成分集剧本");return plan;}

    private List<ObjectNode> draftEpisodes(ObjectNode source,ObjectNode request){
        List<ObjectNode> result=new ArrayList<>();if(request.path("episodes").isArray()&&!request.path("episodes").isEmpty()){request.path("episodes").forEach(node->{if(!node.isObject())throw new IllegalArgumentException("episodes 必须是对象数组");result.add((ObjectNode)node.deepCopy());});return result;}
        int count=request.path("targetEpisodeCount").asInt();if(count<1)throw new IllegalArgumentException("targetEpisodeCount 必须大于 0");List<ObjectNode> chapters=chapters(source);for(int episodeNo=1;episodeNo<=count;episodeNo++){int from=(episodeNo-1)*chapters.size()/count,to=Math.max(from+1,episodeNo*chapters.size()/count);from=Math.min(from,chapters.size()-1);to=Math.min(to,chapters.size());List<ObjectNode> selected=chapters.subList(from,to);ObjectNode episode=obj().put("episodeNo",episodeNo).put("episodeGoal","推进 "+selected.getFirst().path("title").asText()).put("openingHook",episodeNo==1?"建立核心悬念":"承接上一集未决行动").put("centralConflict","围绕本集来源事件推进冲突").put("informationReveal","仅揭示本集可用事实").put("emotionalTurn","关系随事件变化").put("climax","本集核心冲突达到峰值").put("endingHook","保留下一集行动问题");for(ObjectNode chapter:selected){episode.withArray("sourceChapterIds").add(id(chapter));store.list(NOVEL_CHUNK,project(source),id(chapter)).stream().filter(c->c.path("active").asBoolean(true)).forEach(c->episode.withArray("sourceChunkIds").add(id(c)));}result.add(episode);}return result;
    }
    private ObjectNode normalizeEpisode(ObjectNode source,ObjectNode input){
        ObjectNode value=input.deepCopy();int number=value.path("episodeNo").asInt();if(number<1)throw new IllegalArgumentException("episodeNo 必须大于 0");Set<String> chapterIds=chapters(source).stream().map(Documents::id).collect(java.util.stream.Collectors.toSet());Set<String> chunkIds=store.list(NOVEL_CHUNK,project(source),null).stream().filter(c->id(source).equals(text(c,"novelId"))&&c.path("active").asBoolean(true)).map(Documents::id).collect(java.util.stream.Collectors.toSet());if(value.path("sourceChapterIds").isEmpty()||value.path("sourceChunkIds").isEmpty())throw new IllegalArgumentException("每集必须包含来源章节和来源片段");value.path("sourceChapterIds").forEach(node->{if(!chapterIds.contains(node.asText()))throw new IllegalArgumentException("来源章节不属于当前小说");});value.path("sourceChunkIds").forEach(node->{if(!chunkIds.contains(node.asText()))throw new IllegalArgumentException("来源片段不属于当前小说");});
        for(String field:List.of("characters","locations","props","requiredFacts","newFacts","reservedFutureFacts","relationshipChanges","characterChanges","propChanges","locationChanges","sourceRefs"))if(!value.path(field).isArray())value.putArray(field);for(String field:List.of("knowledgeStart","knowledgeEnd","propStateStart","propStateEnd","locationStateStart","locationStateEnd"))if(!value.path(field).isObject())value.putObject(field);
        if(value.path("sourceRefs").isEmpty())value.set("sourceRefs",value.path("sourceChunkIds").deepCopy());Set<String> reserved=new HashSet<>();value.path("reservedFutureFacts").forEach(v->reserved.add(v.asText()));for(String field:List.of("requiredFacts","newFacts"))value.path(field).forEach(v->{if(reserved.contains(v.asText()))throw new WorkflowException("FUTURE_FACT_LEAK","本集使用了保留到未来的事实："+v.asText());});
        value.put("estimatedDurationSec",value.path("estimatedDurationSec").asInt(90)).put("compressionRatio",value.path("compressionRatio").asDouble(1)).put("status","REVIEW");return value;
    }
    private ObjectNode saveEpisode(ObjectNode plan,ObjectNode source,ObjectNode episode){ObjectNode value=episode.deepCopy().put("projectId",project(plan)).put("planId",id(plan)).put("novelId",id(source)).put("analysisRevision",plan.path("analysisRevision").asInt()).put("planVersion",plan.path("version").asInt());return store.create(EPISODE_ADAPTATION_PLAN,value);}
    private ObjectNode coverage(ObjectNode source,List<ObjectNode> episodes,String gapExplanation){Map<String,Integer> counts=new LinkedHashMap<>();chapters(source).forEach(c->counts.put(id(c),0));episodes.forEach(e->e.path("sourceChapterIds").forEach(id->counts.computeIfPresent(id.asText(),(key,count)->count+1)));ObjectNode result=obj();ArrayNode covered=result.putArray("coveredChapterIds"),overlap=result.putArray("overlapChapterIds"),missing=result.putArray("uncoveredChapterIds");counts.forEach((key,count)->{if(count>0)covered.add(key);else missing.add(key);if(count>1)overlap.add(key);});result.put("coverageRatio",counts.isEmpty()?1:(double)covered.size()/counts.size()).put("status",missing.isEmpty()||!gapExplanation.isBlank()?"VALID":"REVIEW_REQUIRED").put("sourceGapExplanation",gapExplanation);return result;}
    private static ArrayNode continuityIssues(List<ObjectNode> episodes){ArrayNode issues=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();List<ObjectNode> ordered=episodes.stream().sorted(Comparator.comparingInt(e->e.path("episodeNo").asInt())).toList();for(int i=1;i<ordered.size();i++){ObjectNode previous=ordered.get(i-1),next=ordered.get(i);compareBoundary(issues,previous,next,"KNOWLEDGE","knowledgeEnd","knowledgeStart");compareBoundary(issues,previous,next,"PROP","propStateEnd","propStateStart");compareBoundary(issues,previous,next,"LOCATION","locationStateEnd","locationStateStart");}return issues;}
    private static void compareBoundary(ArrayNode issues,ObjectNode previous,ObjectNode next,String dimension,String endField,String startField){JsonNode end=previous.path(endField),start=next.path(startField);if(!end.isObject()||!start.isObject())return;end.fieldNames().forEachRemaining(key->{if(start.has(key)&&!Objects.equals(end.path(key),start.path(key))){ObjectNode issue=obj().put("dimension",dimension).put("key",key).put("fromEpisode",previous.path("episodeNo").asInt()).put("toEpisode",next.path("episodeNo").asInt());issue.set("previousEnd",end.path(key).deepCopy());issue.set("nextStart",start.path(key).deepCopy());issues.add(issue);}});}
    private List<ObjectNode> chapters(ObjectNode source){return store.list(NOVEL_CHAPTER,project(source),id(source)).stream().filter(c->c.path("active").asBoolean(true)).sorted(Comparator.comparingDouble(c->c.path("displayOrder").asDouble(c.path("chapterNo").asDouble()))).toList();}
    private static ObjectNode copyPlan(ObjectNode source){ObjectNode value=source.deepCopy();for(String field:List.of("id","parentId","revision","createdAt","updatedAt","confirmedAt","supersededById","diff","revalidatedEpisodes"))value.remove(field);return value;}
    private static ObjectNode copyEpisode(ObjectNode source){ObjectNode value=source.deepCopy();for(String field:List.of("id","parentId","planId","projectId","revision","createdAt","updatedAt","novelId","analysisRevision","planVersion"))value.remove(field);return value;}
    private static void requireRevision(ObjectNode current,JsonNode request){long expected=request.path("revision").asLong(-1);if(expected<1)throw new IllegalArgumentException("修改必须包含 revision");if(expected!=revision(current))throw new com.yourapp.drama.persistence.RevisionConflictException(ADAPTATION_PLAN,id(current));}
    private static List<ObjectNode> objects(JsonNode values){List<ObjectNode> result=new ArrayList<>();values.forEach(value->{if(value.isObject())result.add((ObjectNode)value.deepCopy());});return result;}
}
