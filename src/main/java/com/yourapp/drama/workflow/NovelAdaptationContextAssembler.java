package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class NovelAdaptationContextAssembler {
    private final DocumentStore store;private final NovelAdaptationService adaptations;
    public NovelAdaptationContextAssembler(DocumentStore store,NovelAdaptationService adaptations){this.store=store;this.adaptations=adaptations;}
    public ObjectNode assemble(String planId,int episodeNo,int budgetChars){
        if(budgetChars<256)throw new IllegalArgumentException("context budget 不能小于 256 字符");ObjectNode plan=store.get(ADAPTATION_PLAN,planId);ObjectNode episode=adaptations.episodes(planId).stream().filter(e->e.path("episodeNo").asInt()==episodeNo).findFirst().orElseThrow(()->new IllegalArgumentException("分集不存在："+episodeNo));String projectId=project(plan),novelId=required(plan,"novelId");
        List<ObjectNode> chunks=new ArrayList<>();episode.path("sourceChunkIds").forEach(node->chunks.add(store.get(NOVEL_CHUNK,node.asText())));ObjectNode graph=store.list(NOVEL_STORY_GRAPH,projectId,novelId).stream().filter(g->g.path("analysisRevision").asInt()==plan.path("analysisRevision").asInt()).max(Comparator.comparingInt(g->g.path("analysisRevision").asInt())).orElseThrow(()->new WorkflowException("NOVEL_GRAPH_MISSING","改编计划对应的故事图不存在"));
        ObjectNode context=obj().put("planId",planId).put("episodePlanId",id(episode)).put("episodeNo",episodeNo).put("analysisRevision",plan.path("analysisRevision").asInt()).put("budgetChars",budgetChars);context.set("reservedFutureFacts",episode.path("reservedFutureFacts").deepCopy());context.set("knowledgeStart",episode.path("knowledgeStart").deepCopy());context.set("propStateStart",episode.path("propStateStart").deepCopy());context.set("locationStateStart",episode.path("locationStateStart").deepCopy());
        ArrayNode sourceChunks=context.putArray("sourceChunkIds");chunks.forEach(chunk->sourceChunks.add(id(chunk)));ArrayNode sourceFacts=context.putArray("sourceFactIds");Set<String> chunkIds=new HashSet<>();chunks.forEach(chunk->chunkIds.add(id(chunk)));store.list(STORY_FACT,projectId,null).stream().filter(f->novelId.equals(text(f,"novelId"))&&referencesAny(f.path("sourceRefs"),chunkIds)).forEach(f->sourceFacts.add(id(f)));ArrayNode entityVersions=context.putArray("entityVersionIds");for(String field:List.of("canonicalCharacters","canonicalLocations","canonicalProps"))graph.path(field).forEach(entityVersions::add);
        StringBuilder text=new StringBuilder();append(text,"GLOBAL",graph.path("mainPlot").asText(),budgetChars);store.list(NOVEL_STORY_ARC,projectId,novelId).stream().filter(arc->arc.path("analysisRevision").asInt()==plan.path("analysisRevision").asInt()&&touches(arc,episode)).forEach(arc->append(text,"ARC",arc.path("mainConflict").asText(),budgetChars));if(episodeNo>1)adaptations.episodes(planId).stream().filter(e->e.path("episodeNo").asInt()==episodeNo-1).findFirst().ifPresent(previous->append(text,"PREVIOUS_HANDOFF",previous.path("endingHook").asText(),budgetChars));for(ObjectNode chunk:chunks)append(text,"SOURCE_CHUNK "+id(chunk),text(chunk,"normalizedText"),budgetChars);append(text,"RESERVED_FUTURE_FACTS",episode.path("reservedFutureFacts").toString(),budgetChars);
        context.put("contextText",text.toString()).put("inputChars",text.length());String hash=DependencyRevisionService.hash(context);context.put("contextHash",hash);persist(context,plan,episode,hash);return context;
    }
    private void persist(ObjectNode context,ObjectNode plan,ObjectNode episode,String hash){String projectId=project(plan);Optional<ObjectNode> existing=store.list(PRODUCTION_INPUT_SNAPSHOT,projectId,null).stream().filter(s->hash.equals(text(s,"snapshotKey"))).findFirst();if(existing.isPresent())return;ObjectNode snapshot=obj().put("projectId",projectId).put("sourceKind","EPISODE_ADAPTATION_PLAN").put("sourceId",id(episode)).put("assetSnapshotHash",hash).put("snapshotKey",hash).put("contextHash",hash).put("analysisRevision",plan.path("analysisRevision").asInt());for(String field:List.of("sourceChunkIds","sourceFactIds","entityVersionIds"))snapshot.set(field,context.path(field).deepCopy());store.create(PRODUCTION_INPUT_SNAPSHOT,snapshot);}
    private static boolean referencesAny(JsonNode refs,Set<String> values){for(JsonNode ref:refs)if(values.contains(ref.asText()))return true;return false;}
    private static boolean touches(ObjectNode arc,ObjectNode episode){for(JsonNode chapterId:episode.path("sourceChapterIds")){String ignored=chapterId.asText();if(!ignored.isBlank())return true;}return false;}
    private static void append(StringBuilder target,String label,String value,int budget){if(value==null||value.isBlank()||target.length()>=budget)return;String part="["+label+"]\n"+value+"\n";int available=budget-target.length();target.append(part,0,Math.min(available,part.length()));}
}
