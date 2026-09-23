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

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class NovelAdaptationPlanningIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired NovelAdaptationService adaptations;
    @Autowired NovelAdaptationContextAssembler contexts;

    @Test void planSupportsManyToManySourcesAndPreservesContinuityBoundaries() {
        Fixture f=fixture();
        ObjectNode request=baseRequest(f);
        ArrayNode episodes=request.putArray("episodes");
        episodes.add(episode(1,List.of(f.chapterIds().get(0),f.chapterIds().get(1)),List.of(f.chunkIds().get(0),f.chunkIds().get(1)),"钥匙现身","真相仍被保留"));
        episodes.add(episode(2,List.of(f.chapterIds().get(1)),List.of(f.chunkIds().get(1),f.chunkIds().get(2)),"追踪密门","证人失踪"));
        episodes.add(episode(3,List.of(f.chapterIds().get(2),f.chapterIds().get(3)),List.of(f.chunkIds().get(2),f.chunkIds().get(3)),"揭开身份","幕后人露面"));

        ObjectNode plan=adaptations.create(f.novelId(),request);
        List<ObjectNode> saved=adaptations.episodes(id(plan));
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).path("sourceChapterIds")).hasSize(2);
        assertThat(saved.get(1).path("sourceChapterIds")).containsExactly(idNode(f.chapterIds().get(1)));
        assertThat(strings(saved.get(0).path("reservedFutureFacts"))).containsExactly("幕后人是林川");
        assertThat(saved.get(0).path("knowledgeStart").path("阿宁").asText()).isEqualTo("UNKNOWN");
        assertThat(saved.get(0).path("knowledgeEnd").path("阿宁").asText()).isEqualTo("SUSPECTED");
        assertThat(saved.get(0).path("propStateStart").path("铜铃").asText()).isEqualTo("IN_CHEST");
        assertThat(saved.get(0).path("locationStateEnd").path("旧宅").asText()).isEqualTo("DOOR_OPEN");
        assertThat(plan.path("coverage").path("coveredChapterIds")).hasSize(4);
        assertThat(strings(plan.path("coverage").path("overlapChapterIds"))).contains(f.chapterIds().get(1));
        assertThat(plan.path("continuityIssues").findValues("dimension").stream().map(JsonNode::asText).toList()).contains("KNOWLEDGE","PROP","LOCATION");
        assertThat(text(plan,"status")).isEqualTo("REVIEW");
    }

    @Test void editingOneEpisodeCreatesVersionedDiffAndRevalidatesOnlyNeighbors() {
        Fixture f=fixture();ObjectNode request=baseRequest(f).put("targetEpisodeCount",4);ArrayNode episodes=request.putArray("episodes");
        for(int i=0;i<4;i++)episodes.add(episode(i+1,List.of(f.chapterIds().get(i)),List.of(f.chunkIds().get(i)),"目标"+(i+1),"钩子"+(i+1)));
        ObjectNode v1=adaptations.create(f.novelId(),request);ObjectNode oldEpisode=adaptations.episodes(id(v1)).get(1);
        ObjectNode patch=obj().put("revision",revision(v1)).put("episodePlanRevision",revision(oldEpisode)).put("endingHook","新的第2集钩子");
        patch.putArray("sourceChapterIds").add(f.chapterIds().get(1)).add(f.chapterIds().get(2));
        patch.putArray("sourceChunkIds").add(f.chunkIds().get(1)).add(f.chunkIds().get(2));
        ObjectNode v2=adaptations.editEpisode(id(v1),2,patch);

        assertThat(v2.path("version").asInt()).isEqualTo(2);
        assertThat(text(v2,"supersedesId")).isEqualTo(id(v1));
        assertThat(ints(v2.path("diff").path("changedEpisodes"))).containsExactly(2);
        assertThat(ints(v2.path("revalidatedEpisodes"))).containsExactly(1,2,3);
        assertThat(adaptations.episodes(id(v2))).hasSize(4);
        assertThat(text(adaptations.episodes(id(v2)).get(1),"endingHook")).isEqualTo("新的第2集钩子");
        assertThat(text(store.get(ADAPTATION_PLAN,id(v1)),"status")).isEqualTo("SUPERSEDED");
    }

    @Test void contextIsBudgetedProvenancedAndPersistedWithoutWholeNovelHistory() {
        Fixture f=fixture();ObjectNode request=baseRequest(f).put("targetEpisodeCount",2);ArrayNode episodes=request.putArray("episodes");
        episodes.add(episode(1,List.of(f.chapterIds().get(0)),List.of(f.chunkIds().get(0)),"发现铜铃","门后有脚步"));
        episodes.add(episode(2,List.of(f.chapterIds().get(1)),List.of(f.chunkIds().get(1)),"追踪钥匙","看见密门"));
        ObjectNode plan=adaptations.create(f.novelId(),request);ObjectNode context=contexts.assemble(id(plan),2,650);

        assertThat(context.path("inputChars").asInt()).isLessThanOrEqualTo(650);
        assertThat(strings(context.path("sourceChunkIds"))).containsExactly(f.chunkIds().get(1));
        assertThat(text(context,"contextHash")).hasSize(64);
        assertThat(text(context,"contextText")).contains("第二章钥匙").doesNotContain("第四章幕后人");
        assertThat(strings(context.path("reservedFutureFacts"))).containsExactly("幕后人是林川");
        List<ObjectNode> snapshots=store.list(PRODUCTION_INPUT_SNAPSHOT,f.projectId(),null);
        assertThat(snapshots).anySatisfy(snapshot->{
            assertThat(text(snapshot,"sourceKind")).isEqualTo("EPISODE_ADAPTATION_PLAN");
            assertThat(text(snapshot,"snapshotKey")).isEqualTo(text(context,"contextHash"));
            assertThat(strings(snapshot.path("sourceChunkIds"))).containsExactly(f.chunkIds().get(1));
        });
    }

    @Test void onlyConfirmedPlanCanFeedEpisodeScriptGeneration() {
        Fixture f=fixture();ObjectNode request=baseRequest(f).put("targetEpisodeCount",1).put("sourceGapExplanation","其余章节留待下一季");request.putArray("episodes").add(episode(1,List.of(f.chapterIds().get(0)),List.of(f.chunkIds().get(0)),"发现铜铃","门后有脚步"));
        ObjectNode plan=adaptations.create(f.novelId(),request);
        assertThatThrownBy(()->adaptations.requireConfirmed(id(plan))).isInstanceOf(WorkflowException.class)
            .extracting(e->((WorkflowException)e).code()).isEqualTo("ADAPTATION_PLAN_NOT_CONFIRMED");
        ObjectNode confirmed=adaptations.confirm(id(plan),obj().put("revision",revision(plan)));
        assertThat(text(adaptations.requireConfirmed(id(confirmed)),"status")).isEqualTo("CONFIRMED");
    }

    @Test void reservedFutureFactCannotLeakIntoCurrentEpisode() {
        Fixture f=fixture();ObjectNode request=baseRequest(f).put("targetEpisodeCount",1).put("sourceGapExplanation","其余章节留待下一季");ObjectNode leaking=episode(1,List.of(f.chapterIds().get(0)),List.of(f.chunkIds().get(0)),"提前揭晓","无");leaking.putArray("newFacts").add("幕后人是林川");request.putArray("episodes").add(leaking);
        assertThatThrownBy(()->adaptations.create(f.novelId(),request)).isInstanceOf(WorkflowException.class)
            .extracting(e->((WorkflowException)e).code()).isEqualTo("FUTURE_FACT_LEAK");
    }

    private ObjectNode baseRequest(Fixture f){
        return obj().put("projectId",f.projectId()).put("targetEpisodeCount",3).put("targetEpisodeDuration",90)
            .put("adaptationStyle","SHORT_DRAMA").put("genre","悬疑").put("platform","竖屏短剧").put("region","中国")
            .put("compressionLevel","MEDIUM").put("mainPlotPriority","HIGH").put("subplotPolicy","COMPRESS")
            .put("characterMergePolicy","REVIEW_REQUIRED").put("createdBy","USER");
    }
    private ObjectNode episode(int no,List<String> chapters,List<String> chunks,String goal,String ending){
        ObjectNode value=obj().put("episodeNo",no).put("episodeGoal",goal).put("openingHook","开场钩子").put("centralConflict","寻找真相")
            .put("informationReveal","只揭示本集事实").put("emotionalTurn","信任动摇").put("climax","门被打开").put("endingHook",ending)
            .put("estimatedDurationSec",90).put("compressionRatio",.5);
        chapters.forEach(value.putArray("sourceChapterIds")::add);chunks.forEach(value.putArray("sourceChunkIds")::add);
        for(String field:List.of("characters","locations","props","requiredFacts","newFacts","relationshipChanges","propChanges","locationChanges","sourceRefs"))value.putArray(field);
        value.putArray("reservedFutureFacts").add("幕后人是林川");
        value.putObject("knowledgeStart").put("阿宁","UNKNOWN");value.putObject("knowledgeEnd").put("阿宁","SUSPECTED");
        value.putObject("propStateStart").put("铜铃","IN_CHEST");value.putObject("propStateEnd").put("铜铃","HELD_BY_ANING");
        value.putObject("locationStateStart").put("旧宅","SEALED");value.putObject("locationStateEnd").put("旧宅","DOOR_OPEN");
        return value;
    }
    private Fixture fixture(){
        ObjectNode project=store.create(PROJECT,obj().put("name","改编计划").put("sourceMode","NOVEL"));
        ObjectNode source=store.create(NOVEL_SOURCE,obj().put("projectId",id(project)).put("title","长夜").put("format","TXT").put("storageKey","fixture.txt").put("fileName","fixture.txt").put("fileSize",1000).put("fileHash","b".repeat(64)).put("status","ANALYZED").put("chapterCount",4).put("totalCharacters",400).put("parsingRevision",1).put("analysisRevision",1));
        List<String> chapters=new ArrayList<>(),chunks=new ArrayList<>();
        for(int i=1;i<=4;i++){String body=switch(i){case 1->"第一章铜铃藏在旧宅木箱。";case 2->"第二章钥匙落到阿宁手中。";case 3->"第三章密门开启。";default->"第四章幕后人林川现身。";};ObjectNode chapter=store.create(NOVEL_CHAPTER,obj().put("projectId",id(project)).put("novelId",id(source)).put("chapterNo",i).put("displayOrder",i).put("title","第"+i+"章").put("normalizedText",body).put("rawText",body).put("charCount",body.length()).put("sourceStart",i*100).put("sourceEnd",i*100+body.length()).put("contentHash",NovelIngestionService.hashText(body)).put("active",true).put("parsingRevision",1));ObjectNode chunk=store.create(NOVEL_CHUNK,obj().put("projectId",id(project)).put("novelId",id(source)).put("chapterId",id(chapter)).put("chunkNo",1).put("normalizedText",body).put("charCount",body.length()).put("sourceStart",i*100).put("sourceEnd",i*100+body.length()).put("contentHash",NovelIngestionService.hashText(body)).put("active",true).put("parsingRevision",1));chapters.add(id(chapter));chunks.add(id(chunk));}
        ObjectNode graph=obj().put("projectId",id(project)).put("novelId",id(source)).put("analysisRevision",1).put("profile","STANDARD").put("mainPlot","铜铃、钥匙与密门的真相");for(String field:List.of("canonicalCharacters","canonicalLocations","canonicalProps","subPlots","storyFacts","relationships","characterKnowledge","timelineEvents","worldRules","storyArcs","foreshadowing","payoffs","characterArcs"))graph.putArray(field);store.create(NOVEL_STORY_GRAPH,graph);
        ObjectNode fact=store.create(STORY_FACT,obj().put("projectId",id(project)).put("novelId",id(source)).put("factKey","mastermind").put("statement","幕后人是林川").put("predicate","IDENTITY").put("validFromStoryTime",4).put("status","ACTIVE").put("source","NOVEL"));
        return new Fixture(id(project),id(source),chapters,chunks,id(fact));
    }
    private static JsonNode idNode(String value){return com.fasterxml.jackson.databind.node.TextNode.valueOf(value);}
    private static List<String> strings(JsonNode values){List<String> result=new ArrayList<>();values.forEach(value->result.add(value.asText()));return result;}
    private static List<Integer> ints(JsonNode values){List<Integer> result=new ArrayList<>();values.forEach(value->result.add(value.asInt()));return result;}
    private record Fixture(String projectId,String novelId,List<String> chapterIds,List<String> chunkIds,String futureFactId){}
}
