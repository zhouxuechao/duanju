package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class NovelToScriptE2EIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired NovelAnalysisService analysis;
    @Autowired NovelAdaptationService adaptations;
    @Autowired EpisodeAdaptationService episodeAdaptation;
    @Autowired ScriptWorkspaceService scripts;
    @Autowired SourceTraceabilityService traces;

    @Test void threeChapterNovelFlowsThroughReviewEditConfirmAndBidirectionalTraceWithoutProviders(){
        Fixture f=fixture(3);analysis.start(f.novelId(),obj().put("confirmed",true).put("profile","STANDARD"));
        List<ObjectNode> characters=store.list(com.yourapp.drama.persistence.ResourceKind.CHARACTER,f.projectId(),null),locations=store.list(LOCATION,f.projectId(),null),props=store.list(PROP,f.projectId(),null);
        assertThat(characters).hasSize(2);assertThat(locations).hasSize(1);assertThat(props).hasSize(1);
        ObjectNode request=planRequest(f,1);ObjectNode episode=request.withArray("episodes").addObject().put("episodeNo",1).put("episodeGoal","找到密门").put("openingHook","铜铃响了").put("centralConflict","老人阻止开门").put("climax","钥匙插入锁孔").put("endingHook","门后传来脚步");f.chapterIds().forEach(episode.withArray("sourceChapterIds")::add);f.chunkIds().forEach(episode.withArray("sourceChunkIds")::add);for(String field:List.of("characters","locations","props","requiredFacts","newFacts","reservedFutureFacts","relationshipChanges","sourceRefs"))episode.putArray(field);characters.forEach(value->episode.withArray("characters").add(id(value)));locations.forEach(value->episode.withArray("locations").add(id(value)));props.forEach(value->episode.withArray("props").add(id(value)));episode.putObject("knowledgeStart");episode.putObject("knowledgeEnd");episode.putObject("propStateStart");episode.putObject("propStateEnd");episode.putObject("locationStateStart");episode.putObject("locationStateEnd");episode.putArray("characterChanges").add(obj().put("entityId",id(characters.getFirst())).put("storyTime",10).put("state","INJURED").put("physicalCondition","左臂受伤"));episode.putArray("propChanges").add(obj().put("entityId",id(props.getFirst())).put("storyTime",10).put("state","CRACKED").put("condition","CRACKED").put("visible",true));episode.putArray("locationChanges").add(obj().put("entityId",id(locations.getFirst())).put("storyTime",10).put("state","DOOR_OPEN"));
        ObjectNode plan=adaptations.create(f.novelId(),request);plan=adaptations.confirm(id(plan),obj().put("revision",revision(plan)));

        ObjectNode v1=episodeAdaptation.generate(id(plan),1,obj().put("contextBudgetChars",1800));
        assertThat(text(v1,"status")).isEqualTo("REVIEW");assertThat(text(v1,"sourceMode")).isEqualTo("NOVEL");assertThat(v1.path("structuredContent").path("scenes")).isNotEmpty();assertThat(v1.path("structuredContent").path("scenes").get(0).path("sourceRefs")).isNotEmpty();assertThat(v1.path("structuredContent").path("scenes").get(0).path("actions")).isNotEmpty();
        Set<String> definitionIds=List.of(CHARACTER_DEFINITION_VERSION,LOCATION_DEFINITION_VERSION,PROP_DEFINITION_VERSION).stream().flatMap(kind->store.list(kind,f.projectId(),null).stream()).map(Documents::id).collect(Collectors.toSet());assertThat(v1.path("sourceProvenance").path("entityVersionIds")).allSatisfy(node->assertThat(definitionIds).contains(node.asText()));
        JsonNode dialogue=v1.path("structuredContent").path("scenes").get(0).path("dialogues").get(0);assertThat(text(dialogue,"sourceType")).isIn("ORIGINAL_QUOTE","ADAPTED","AI_CREATED");assertThat(dialogue.path("sourceRefs")).isNotEmpty();
        assertThat(store.list(com.yourapp.drama.persistence.ResourceKind.CHARACTER,f.projectId(),null)).hasSize(2);assertThat(store.list(CHARACTER_STATE,f.projectId(),id(characters.getFirst()))).hasSize(1);assertThat(store.list(PROP_STATE,f.projectId(),id(props.getFirst()))).hasSize(1);assertThat(store.list(LOCATION_STATE,f.projectId(),id(locations.getFirst()))).hasSize(1);
        ObjectNode draft=scripts.fork(required(v1,"episodeId"),obj().put("revision",revision(v1)).put("createdBy","USER"));ObjectNode edited=draft.path("structuredContent").deepCopy();((ObjectNode)edited).put("summary","人工调整后的摘要");ObjectNode v2=scripts.update(id(draft),obj().put("revision",revision(draft)).set("structuredContent",edited));ObjectNode confirmed=scripts.confirm(id(v2),obj().put("revision",revision(v2)));assertThat(text(confirmed,"status")).isEqualTo("CONFIRMED");assertThat(store.list(EPISODE_SCRIPT_VERSION,f.projectId(),required(v1,"episodeId"))).hasSize(2);

        ObjectNode direct=traces.scriptElement(id(v1),"DIALOGUE",text(dialogue,"lineKey"));assertThat(direct.path("novelChunks")).isNotEmpty();assertThat(direct.path("novelChapters")).isNotEmpty();ObjectNode editedTrace=traces.scriptVersion(id(confirmed));assertThat(editedTrace.path("novelChunks")).isNotEmpty();assertThat(text(editedTrace.path("adaptationPlan"),"id")).isEqualTo(id(plan));assertThat(text(confirmed,"contextHash")).isEqualTo(text(v1,"contextHash"));
        ObjectNode reverse=traces.reverseChapter(f.chapterIds().getFirst());assertThat(reverse.path("episodes")).isNotEmpty();assertThat(reverse.path("scenes")).isNotEmpty();assertThat(reverse.path("dialogues")).isNotEmpty();
        assertThat(store.list(GENERATION_JOB,f.projectId(),null)).isEmpty();
    }

    @Test void syntheticHundredChapterNovelKeepsEpisodeContextBoundedAndTraceable(){
        Fixture f=fixture(100);analysis.start(f.novelId(),obj().put("confirmed",true).put("profile","FAST"));ObjectNode plan=adaptations.create(f.novelId(),planRequest(f,20));plan=adaptations.confirm(id(plan),obj().put("revision",revision(plan)));ObjectNode script=episodeAdaptation.generate(id(plan),20,obj().put("contextBudgetChars",1200));
        assertThat(script.path("sourceProvenance").path("contextInputChars").asInt()).isLessThanOrEqualTo(1200);assertThat(script.path("sourceProvenance").has("wholeNovelText")).isFalse();assertThat(text(script,"status")).isEqualTo("REVIEW");assertThat(traces.scriptVersion(id(script)).path("novelChapters")).isNotEmpty();assertThat(store.list(GENERATION_JOB,f.projectId(),null)).isEmpty();
    }

    @Test void mediaTraceReachesScriptAdaptationAndNovelSource(){
        Fixture f=fixture(3);analysis.start(f.novelId(),obj().put("confirmed",true));ObjectNode plan=adaptations.create(f.novelId(),planRequest(f,1));plan=adaptations.confirm(id(plan),obj().put("revision",revision(plan)));ObjectNode script=episodeAdaptation.generate(id(plan),1,obj().put("contextBudgetChars",1200));String episodeId=required(script,"episodeId");ObjectNode scene=store.create(SCENE,obj().put("projectId",f.projectId()).put("episodeId",episodeId).put("name","溯源场"));ObjectNode shot=store.create(SHOT,obj().put("projectId",f.projectId()).put("sceneId",id(scene)).put("duration",3).put("status","PLANNED"));ObjectNode prompt=store.create(PROMPT_VERSION,obj().put("projectId",f.projectId()).put("shotId",id(shot)).put("version",1).put("prompt","测试"));String hash="c".repeat(64);ObjectNode snapshot=obj().put("projectId",f.projectId()).put("sourceKind","SHOT").put("sourceId",id(shot)).put("promptVersionId",id(prompt)).put("scriptVersionId",id(script)).put("assetSnapshotHash",hash).put("snapshotKey","media-"+id(shot));snapshot.set("sourceChunkIds",script.path("sourceProvenance").path("sourceChunkIds").deepCopy());snapshot=store.create(PRODUCTION_INPUT_SNAPSHOT,snapshot);ObjectNode keyframe=store.create(KEYFRAME,obj().put("projectId",f.projectId()).put("shotId",id(shot)).put("version",1).put("provider","MOCK").put("providerUrl","https://media.example/frame.png").put("promptVersionId",id(prompt)).put("productionInputSnapshotId",id(snapshot)));ObjectNode take=store.create(VIDEO_TAKE,obj().put("projectId",f.projectId()).put("shotId",id(shot)).put("takeNo",1).put("provider","MOCK").put("model","fake").put("promptVersionId",id(prompt)).put("sourceKeyframeId",id(keyframe)).put("sourceProviderUrlSnapshot","https://media.example/frame.png").put("productionInputSnapshotId",id(snapshot)));
        ObjectNode trace=traces.media(VIDEO_TAKE,id(take));assertThat(text(trace.path("scriptVersion"),"id")).isEqualTo(id(script));assertThat(text(trace.path("adaptationPlan"),"id")).isEqualTo(id(plan));assertThat(trace.path("novelChunks")).isNotEmpty();assertThat(text(trace.path("novelSource"),"id")).isEqualTo(f.novelId());
        ObjectNode timeline=store.create(TIMELINE,obj().put("projectId",f.projectId()).put("episodeId",episodeId).put("scriptVersionId",id(script)));store.create(TIMELINE_ITEM,obj().put("projectId",f.projectId()).put("timelineId",id(timeline)).put("shotId",id(shot)).put("videoTakeId",id(take)).put("track","VIDEO"));ObjectNode renderInput=obj().put("timelineId",id(timeline)).put("scriptVersionId",id(script));ObjectNode render=store.create(GENERATION_JOB,obj().put("projectId",f.projectId()).put("type","RENDER").put("status","SUCCESS").put("requestKey","render-trace"+id(timeline)).set("inputSnapshot",renderInput));
        ObjectNode renderTrace=traces.media(GENERATION_JOB,id(render));assertThat(text(renderTrace.path("timeline"),"id")).isEqualTo(id(timeline));assertThat(renderTrace.path("mediaLineages")).hasSize(1);assertThat(text(renderTrace.path("mediaLineages").get(0).path("scriptVersion"),"id")).isEqualTo(id(script));assertThat(text(renderTrace.path("mediaLineages").get(0).path("novelSource"),"id")).isEqualTo(f.novelId());
    }

    private ObjectNode planRequest(Fixture f,int episodes){return obj().put("projectId",f.projectId()).put("targetEpisodeCount",episodes).put("targetEpisodeDuration",90).put("adaptationStyle","BALANCED").put("createdBy","USER");}
    private Fixture fixture(int count){ObjectNode project=store.create(PROJECT,obj().put("name","小说转剧本").put("idea","来源小说").put("sourceMode","NOVEL"));ObjectNode source=store.create(NOVEL_SOURCE,obj().put("projectId",id(project)).put("title","旧宅铃声").put("format","TXT").put("storageKey","fixture.txt").put("fileName","fixture.txt").put("fileSize",count*100).put("fileHash","a".repeat(64)).put("status","READY").put("chapterCount",count).put("totalCharacters",count*50).put("parsingRevision",1));List<String> chapters=new ArrayList<>(),chunks=new ArrayList<>();for(int i=1;i<=count;i++){String body="人物：林川,阿宁；地点：旧宅；道具：铜铃；事实：第"+i+"章线索出现。";ObjectNode chapter=store.create(NOVEL_CHAPTER,obj().put("projectId",id(project)).put("novelId",id(source)).put("chapterNo",i).put("displayOrder",i).put("title","第"+i+"章").put("normalizedText",body).put("rawText",body).put("charCount",body.length()).put("sourceStart",i*100).put("sourceEnd",i*100+body.length()).put("contentHash",NovelIngestionService.hashText(body)).put("active",true).put("parsingRevision",1));ObjectNode chunk=store.create(NOVEL_CHUNK,obj().put("projectId",id(project)).put("novelId",id(source)).put("chapterId",id(chapter)).put("chunkNo",1).put("normalizedText",body).put("charCount",body.length()).put("sourceStart",i*100).put("sourceEnd",i*100+body.length()).put("contentHash",NovelIngestionService.hashText(body)).put("active",true).put("parsingRevision",1));store.create(SOURCE_REFERENCE,obj().put("projectId",id(project)).put("sourceType","NOVEL").put("novelId",id(source)).put("chapterId",id(chapter)).put("chunkId",id(chunk)).put("startOffset",i*100).put("endOffset",i*100+body.length()).put("contentHash",NovelIngestionService.hashText(body)));chapters.add(id(chapter));chunks.add(id(chunk));}return new Fixture(id(project),id(source),chapters,chunks);}
    private record Fixture(String projectId,String novelId,List<String> chapterIds,List<String> chunkIds){}
}
