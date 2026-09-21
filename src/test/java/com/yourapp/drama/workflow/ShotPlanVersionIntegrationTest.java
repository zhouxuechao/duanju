package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.*;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.persistence.ResourceKind.CHARACTER;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShotPlanVersionIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired WorkflowService workflow;
    @Autowired AssetViewService assets;
    @Autowired CreativeJobs creative;
    @Autowired JobService jobs;
    private String projectId,sceneId,locationId;

    @BeforeEach void setup(){
        ObjectNode project=store.create(PROJECT,obj().put("name","拆镜版本").put("idea","僵尸、农村、一群留守老人").put("dialect","MANDARIN").put("episodeCount",1));projectId=id(project);
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",projectId).put("name","第一集").put("continuityHash","fixture-core").put("script","老人站在院门内倾听"));
        ObjectNode actor=store.create(CHARACTER,obj().put("projectId",projectId).put("characterKey","elder").put("name","周伯").put("provider","SEEDREAM").put("sourceType","IMAGE_REFERENCE").put("providerStatus","UNBOUND"));
        ObjectNode look=store.create(CHARACTER_LOOK,obj().put("projectId",projectId).put("characterId",id(actor)).put("name","蓝棉袄").put("description","旧蓝棉袄黑布鞋"));
        actor=store.update(CHARACTER,id(actor),revision(actor),actor.deepCopy().put("baseLookId",id(look)));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",projectId).put("locationKey","yard").put("name","院落").put("description","北门东井"));locationId=id(location);
        NewWorkflowTestFixtures.install(store,assets,project,episode,actor,look,location);
        ObjectNode currentEpisode=store.get(EPISODE,id(episode));String coreId=text(currentEpisode,"storyBibleId");
        ObjectNode content=obj().put("script","老人站在院门内倾听");content.putArray("characterKeys").add("elder");content.putArray("locationKeys").add("yard");content.putArray("propKeys");
        ObjectNode script=store.create(STORY_DOCUMENT,obj().put("projectId",projectId).put("coreId",coreId).put("documentType","EPISODE_SCRIPT").put("reviewStatus","CONFIRMED").put("version",2).set("content",content));
        store.update(EPISODE,id(currentEpisode),revision(currentEpisode),currentEpisode.deepCopy().put("storyDocumentId",id(script)));
        sceneId=id(store.create(SCENE,obj().put("projectId",projectId).put("episodeId",id(episode)).put("name","院门").put("description","老人听门环声").put("duration",6)));
    }

    @Test void unchangedConfirmedScriptAndReferencesReuseTheQueuedAndSuccessfulPlan(){
        for(int i=0;i<25;i++)store.create(PROP,obj().put("projectId",projectId).put("storyBibleId",text(store.get(PROJECT,projectId),"activeStoryDocumentId")).put("name","本集不出现的旧道具"+i).put("propKey","unused-"+i));
        ObjectNode plan=workflow.plan(sceneId,obj());assertThat(id(workflow.plan(sceneId,obj()))).isEqualTo(id(plan));
        ObjectNode plannedScene=store.get(SCENE,sceneId);
        assertThat(text(plannedScene,"activeDirectorPlanJobId")).isEqualTo(id(plan));
        assertThat(text(plannedScene,"activeDirectorPlanSignature")).isNotBlank();
        assertThat(plannedScene.has("activeShotPlanJobId")||plannedScene.has("activeShotPlanSignature")).isFalse();
        assertThat(plan.path("inputSnapshot").path("assets").path("props")).isEmpty();
        assertThat(plan.path("inputSnapshot").path("assets").toString()).doesNotContain("sourceSnapshot","providerUrl");
        run(plan);assertPersistedSceneState(store.list(SHOT,projectId,sceneId));assertThat(id(workflow.plan(sceneId,obj()))).isEqualTo(id(plan));
        assertThat(store.list(GENERATION_JOB,projectId,null).stream().filter(j->"DIRECTOR_PLAN".equals(text(j,"type")))).hasSize(1);
        assertThat(store.list(SHOT,projectId,sceneId)).hasSize(2);
    }
    @Test void savedShotsPinDirectorBeatAndShotPlanVersions(){
        ObjectNode plan=workflow.plan(sceneId,obj());run(plan);ObjectNode shot=store.list(SHOT,projectId,sceneId).getFirst();
        assertThat(shot.path("directorPlanVersion").asInt()).isEqualTo(1);assertThat(shot.path("dramaticBeatVersion").asInt()).isEqualTo(1);assertThat(shot.path("shotPlanVersion").asInt()).isEqualTo(1);
        assertThat(shot.path("directorPlanSnapshot").path("planPurpose").asText()).isNotBlank();
        assertThat(shot.path("dramaticBeatSnapshot").path("beatId").asText()).isEqualTo(shot.path("beatId").asText());
        assertThat(shot.path("shotPlanSnapshot").path("directorIntent").asText()).isEqualTo(shot.path("directorIntent").asText());
    }
    @Test void longSceneDemoUsesVariableDurationsInsteadOfEqualSlices(){
        ObjectNode scene=store.get(SCENE,sceneId);store.update(SCENE,sceneId,revision(scene),scene.deepCopy().put("duration",24));
        ObjectNode plan=workflow.plan(sceneId,obj());run(plan);List<ObjectNode> shots=store.list(SHOT,projectId,sceneId);
        assertThat(shots).hasSizeGreaterThanOrEqualTo(6);assertThat(shots.stream().map(s->s.path("duration").asDouble()).distinct()).hasSizeGreaterThan(1);
    }
    @Test void twentyFourSecondSceneUsesPersistedDirectorPlanAndRecoverableDetailBatches(){
        ObjectNode scene=store.get(SCENE,sceneId);store.update(SCENE,sceneId,revision(scene),scene.deepCopy().put("duration",24));
        ObjectNode plan=workflow.plan(sceneId,obj());
        assertThat(text(plan,"type")).isEqualTo("DIRECTOR_PLAN");
        run(plan);
        ObjectNode completedPlan=store.get(GENERATION_JOB,id(plan));
        assertThat(completedPlan.path("outputSnapshot").path("providerInputChars").asInt()).isLessThan(10_000);
        assertThat(completedPlan.path("outputSnapshot").path("schemaChars").asInt()).isLessThan(14_000);
        List<ObjectNode> batches=store.list(GENERATION_JOB,projectId,null).stream().filter(j->"SHOT_DETAIL".equals(text(j,"type"))).sorted(Comparator.comparingInt(j->j.path("inputSnapshot").path("batchIndex").asInt())).toList();
        assertThat(batches).hasSizeGreaterThanOrEqualTo(2).allSatisfy(j->{assertThat(text(j,"status")).isEqualTo("SUCCESS");assertThat(j.path("inputSnapshot").path("shotSkeletons").size()).isBetween(1,4);assertThat(j.path("outputSnapshot").path("providerInputChars").asInt()).isLessThan(10_000);assertThat(j.path("outputSnapshot").path("schemaChars").asInt()).isLessThan(14_000);});
        for(int i=1;i<batches.size();i++){
            JsonNode previous=batches.get(i-1).path("outputSnapshot"),current=batches.get(i).path("inputSnapshot");
            assertThat(current.path("currentState")).isEqualTo(previous.path("finalContinuity"));
            assertThat(current.path("previousShotContinuity").path("blocking")).isEqualTo(previous.path("lastShotContinuity").path("blocking"));
        }
        List<ObjectNode> shots=store.list(SHOT,projectId,sceneId).stream().filter(s->!s.path("stale").asBoolean()).toList();
        assertThat(shots).hasSizeBetween(8,12);
        assertThat(shots.stream().mapToDouble(s->s.path("duration").asDouble()).sum()).isCloseTo(24,within(0.01));
        assertThat(shots).allSatisfy(s->{assertThat(s.path("startState").isObject()).isTrue();assertThat(s.path("endState").isObject()).isTrue();assertThat(text(s,"directorPlanJobId")).isEqualTo(id(plan));});
    }
    @Test void sixtySecondSceneStaysWithinDurationDrivenShotBoundsAndStrictSingleShotRequests(){
        ObjectNode scene=store.get(SCENE,sceneId);store.update(SCENE,sceneId,revision(scene),scene.deepCopy().put("duration",60));
        ObjectNode plan=workflow.plan(sceneId,obj());run(plan);
        List<ObjectNode> shots=store.list(SHOT,projectId,sceneId).stream().filter(s->!s.path("stale").asBoolean()).toList();
        assertThat(shots).hasSizeBetween(16,25);
        assertThat(shots.stream().mapToDouble(s->s.path("duration").asDouble()).sum()).isCloseTo(60,within(0.01));
        assertThat(shots.stream().map(s->text(s,"beatId")).distinct()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(shots.stream().map(s->text(s.path("dramaticBeatSnapshot"),"emotionAfter")).distinct()).contains("怀疑","紧张","恐惧");
        assertThat(shots.stream().map(s->text(s,"shotSize")).distinct()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(shots.stream().map(s->text(s,"directorIntent"))).contains("SHOW_REACTION");
        assertThat(shots).allSatisfy(s->assertThat(text(s.path("blocking"),"axisSide")).isEqualTo("A_SIDE"));
        assertThat(store.list(GENERATION_JOB,projectId,null).stream().filter(j->"SHOT_DETAIL".equals(text(j,"type"))))
            .hasSize(shots.size()).allSatisfy(j->assertThat(j.path("inputSnapshot").path("shotSkeletons")).hasSize(1));
    }
    @Test void directorReceivesOneNormalizedSceneDurationWhenProjectAndSceneDiffer(){
        ObjectNode project=store.get(PROJECT,projectId);store.update(PROJECT,projectId,revision(project),project.deepCopy().put("targetDuration",12));
        ObjectNode scene=store.get(SCENE,sceneId);store.update(SCENE,sceneId,revision(scene),scene.deepCopy().put("duration",24));
        ObjectNode plan=workflow.plan(sceneId,obj()),input=(ObjectNode)plan.path("inputSnapshot");
        assertThat(input.path("sceneTargetDurationSeconds").asDouble()).isEqualTo(24);
        assertThat(input.path("project").has("targetDuration")).isFalse();
    }
    @Test void scenePlanRequestCanOverrideProjectDirectorStyleAsData(){
        ObjectNode request=obj();request.putObject("directorStyleOverride").put("averageShotLength",2.2).put("reactionShotPreference",0.9);
        ObjectNode plan=workflow.plan(sceneId,request),profile=(ObjectNode)plan.path("inputSnapshot").path("directorStyleProfile");
        assertThat(profile.path("averageShotLength").asDouble()).isEqualTo(2.2);assertThat(profile.path("reactionShotPreference").asDouble()).isEqualTo(0.9);assertThat(profile.path("cameraActivity").asText()).isNotBlank();
    }

    @Test void replacingAReferenceCreatesANewPlanAndRetiresOldShotsWithoutDeletingHistory(){
        ObjectNode first=workflow.plan(sceneId,obj());run(first);List<ObjectNode> oldShots=store.list(SHOT,projectId,sceneId);
        String episodeId=text(store.get(SCENE,sceneId),"episodeId");
        ObjectNode timeline=store.create(TIMELINE,obj().put("projectId",projectId).put("episodeId",episodeId));
        store.create(TIMELINE_ITEM,obj().put("projectId",projectId).put("timelineId",id(timeline)).put("shotId",id(oldShots.getFirst())));
        replaceLocationViews();ObjectNode second=workflow.plan(sceneId,obj());
        assertThat(id(second)).isNotEqualTo(id(first));assertThat(second.path("inputSnapshot").path("planVersion").asInt()).isEqualTo(2);
        assertThat(store.get(TIMELINE,id(timeline)).path("stale").asBoolean()).isTrue();
        assertThat(oldShots).allSatisfy(s->assertThat(store.get(SHOT,id(s)).path("stale").asBoolean()).isTrue());
        run(second);List<ObjectNode> current=store.list(SHOT,projectId,sceneId).stream().filter(s->!s.path("stale").asBoolean()).sorted(Comparator.comparingInt(s->s.path("shotNo").asInt())).toList();assertPersistedSceneState(current);
        assertThat(store.list(SHOT,projectId,sceneId)).hasSize(4);assertThat(current).hasSize(2);
        assertThat(current.getFirst().path("shotNo").asInt()).isEqualTo(1);
        assertThat(id(workflow.context(current.get(1)).path("previousShot"))).isEqualTo(id(current.getFirst()));
        assertThat(id(workflow.plan(sceneId,obj()))).isEqualTo(id(second));
    }

    @Test void anObsoleteQueuedPlanCannotAppendShotsIntoTheCurrentVersion(){
        ObjectNode first=workflow.plan(sceneId,obj());replaceLocationViews();ObjectNode second=workflow.plan(sceneId,obj());
        assertThatThrownBy(()->creative.process(first)).isInstanceOf(WorkflowException.class);
        assertThat(store.list(SHOT,projectId,sceneId)).isEmpty();run(second);assertThat(store.list(SHOT,projectId,sceneId)).hasSize(2);
    }

    private void run(ObjectNode plan){
        ObjectNode current=plan;
        while(current!=null){ObjectNode running=jobs.mutate(id(current),j->j.put("status","RUNNING").put("attempts",j.path("attempts").asInt()+1));creative.process(running);current=jobs.claim().orElse(null);}
        assertThat(text(store.get(GENERATION_JOB,id(plan)),"status")).isEqualTo("SUCCESS");
    }
    private void replaceLocationViews(){
        assets.invalidateAsset(projectId,locationId);ObjectNode request=obj();request.putArray("assetIds").add(locationId);assets.generate(projectId,request);
        for(ObjectNode job:store.list(GENERATION_JOB,projectId,null))if("ASSET_IMAGE".equals(text(job,"type"))&&"QUEUED".equals(text(job,"status")))jobs.cancel(id(job));
        List<ObjectNode> current=store.list(ASSET_VIEW,projectId,locationId).stream().filter(v->!v.path("stale").asBoolean()).toList();String master=id(current.stream().filter(v->v.path("master").asBoolean()).findFirst().orElseThrow());
        for(ObjectNode view:current){ObjectNode approved=view.deepCopy().put("status","APPROVED").put("approved",true).put("providerUrl","https://fixture.invalid/"+id(view)+".png").put("compilerVersion","4.2.0-location-topology");if(!view.path("master").asBoolean())approved.putArray("referenceViewIds").add(master);store.update(ASSET_VIEW,id(view),revision(view),approved);}
    }
    private void assertPersistedSceneState(List<ObjectNode> shots){
        assertThat(shots).isNotEmpty().allSatisfy(shot->{
            assertThat(text(shot,"locationId")).isEqualTo(locationId);
            ObjectNode start=(ObjectNode)shot.path("startState"),end=(ObjectNode)shot.path("endState");
            for(String field:List.of("locationId","time","lighting","spatialRelations")){
                assertThat(start.hasNonNull(field)).as("startState.%s",field).isTrue();
                assertThat(end.hasNonNull(field)).as("endState.%s",field).isTrue();
                assertThat(end.path(field)).as("endState.%s matches startState",field).isEqualTo(start.path(field));
            }
        });
    }
}
