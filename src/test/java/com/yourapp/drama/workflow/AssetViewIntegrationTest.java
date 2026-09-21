package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.production.AssetDependencyAnalyzer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.persistence.ResourceKind.CHARACTER;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AssetViewIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired AssetViewService assets;
    @Autowired JobService jobs;
    @MockitoBean ImageGenerator images;
    private String projectId,coreId,lookId,locationId,propId;

    @BeforeEach void setup(){
        ObjectNode project=store.create(PROJECT,obj().put("name","参考图测试").put("idea","农村僵尸与留守老人").put("episodeCount",1));projectId=id(project);
        ObjectNode core=store.create(STORY_DOCUMENT,obj().put("projectId",projectId).put("reviewStatus","CONFIRMED").put("documentType","CORE").put("version",1));coreId=id(core);
        store.update(PROJECT,projectId,revision(project),project.deepCopy().put("activeStoryDocumentId",coreId));
        ObjectNode actor=store.create(CHARACTER,obj().put("projectId",projectId).put("storyBibleId",coreId).put("name","周伯").put("description","七十岁，瘦削，左颊有斑").put("provider","SEEDREAM").put("sourceType","IMAGE_REFERENCE").put("providerStatus","UNBOUND"));
        lookId=id(store.create(CHARACTER_LOOK,obj().put("projectId",projectId).put("storyBibleId",coreId).put("characterId",id(actor)).put("name","蓝布棉袄").put("description","洗白蓝布棉袄、黑布鞋")));
        store.update(CHARACTER,id(actor),revision(actor),actor.deepCopy().put("baseLookId",lookId));
        ObjectNode location=obj().put("projectId",projectId).put("storyBibleId",coreId).put("locationKey","yard").put("name","祠堂院落").put("description","北门南井，东侧有槐树");
        ObjectNode bible=obj().put("layout","长方形院落，北为正门，南侧水井，东侧槐树");
        bible.set("coordinateSystem",obj().put("origin","院落中心地面").put("northAxis","朝北门为北").put("eastAxis","朝槐树为东").put("verticalAxis","垂直地面向上"));
        bible.set("dimensions",obj().put("width","东西八米").put("depth","南北十米").put("height","围墙三米"));
        bible.putArray("surfaces").add(obj().put("surfaceId","GROUND").put("name","青石地面").put("kind","GROUND").put("worldOrientation","HORIZONTAL").put("bounds","东西八米、南北十米").put("material","旧青石").put("appearance","灰黑、缝隙有青苔"))
                .add(obj().put("surfaceId","NORTH_WALL").put("name","北墙").put("kind","WALL").put("worldOrientation","NORTH").put("bounds","宽八米、高三米").put("material","灰砖").put("appearance","中部设木门"));
        bible.putArray("fixedFeatures").add(obj().put("featureId","NORTH_GATE").put("name","北门").put("kind","DOOR").put("supportSurfaceId","NORTH_WALL").put("worldPosition","北墙正中").put("size","宽两米、高二点四米").put("state","关闭").put("appearance","褪色双扇木门"))
                .add(obj().put("featureId","SOUTH_WELL").put("name","水井").put("kind","WELL").put("supportSurfaceId","GROUND").put("worldPosition","原点以南三米").put("size","直径一米、高零点八米").put("state","井盖半掩").put("appearance","青石井圈"));
        bible.putArray("spatialRelations").add(obj().put("subjectId","NORTH_GATE").put("relation","NORTH_OF").put("objectId","SOUTH_WELL").put("distance","约八米"));
        bible.putArray("lightSources").add(obj().put("lightId","WEST_SUN").put("kind","SUN").put("worldPosition","西侧天空").put("direction","由西向东").put("colorTemperature","暖色夕阳").put("appearance","低角度斜射"));
        bible.putArray("visualInvariants").add("北门始终位于北墙正中").add("水井始终位于院落南半部");bible.putArray("prohibitedElements").add("牌位");
        location.set("locationBible",bible);locationId=id(store.create(LOCATION,location));
        propId=id(store.create(PROP,obj().put("projectId",projectId).put("storyBibleId",coreId).put("propKey","bell").put("name","铜铃").put("description","拇指大的缺口铜铃")));
        when(images.generate(any())).thenAnswer(call->new ImageGenerator.ImageResult("https://mock.volcengine.invalid/"+UUID.randomUUID()+".png",Instant.now().plusSeconds(3600),"image-request-"+UUID.randomUUID(),"mock-seedream",true));
    }

    @Test void createsSeparatePlansAndOnlyThreeMastersWithoutDuplicates(){
        assertThat(assets.generate(projectId,obj()).path("views")).hasSize(12);
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(3).allSatisfy(j->{assertThat(text(j,"type")).isEqualTo("ASSET_IMAGE");assertThat(j.path("maxAttempts").asInt()).isEqualTo(1);assertThat(j.path("inputSnapshot").path("imageTaskType").asText()).isEqualTo("ASSET_REFERENCE");assertThat(j.path("inputSnapshot").path("compilerVersion").asText()).isIn("4.0.0-asset-reference","4.1.0-character-turnaround","4.2.0-location-topology");assertThat(j.path("inputSnapshot").path("normalizedPromptHash").asText()).hasSize(64);assertThat(j.path("inputSnapshot").path("referenceBindingsHash").asText()).hasSize(64);assertThat(j.path("inputSnapshot").path("providerCapabilitiesVersion").asText()).isNotBlank();});
        assets.generate(projectId,obj());assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(3);
        assertThat(assets.approvedReferences(projectId,coreId,lookId)).isEmpty();
        assertThatThrownBy(()->assets.requireReady(projectId,coreId,List.of(lookId))).isInstanceOf(WorkflowException.class);
    }

    @Test void masterMustBeApprovedBeforeDependentViewsAndEveryChildUsesExactlyThatMaster(){
        generateLook();ObjectNode scheduled=current(lookId,"FRONT");
        assertThatThrownBy(()->approve(scheduled)).isInstanceOf(WorkflowException.class);
        ObjectNode master=run(scheduled);assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(1);
        ObjectNode approved=approve(master);
        approve(master);
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(4);
        for(String angle:List.of("LEFT","RIGHT","BACK")){
            ObjectNode child=current(lookId,angle);assertThat(child.path("referenceViewIds").get(0).asText()).isEqualTo(id(approved));
            child=run(child);approve(child);
        }
        assets.requireReady(projectId,coreId,List.of(lookId));
        assertThat(assets.approvedReferences(projectId,coreId,lookId)).hasSize(4);
        verify(images,times(3)).generate(argThat(r->r.referenceImageUrls().equals(List.of(text(approved,"providerUrl")))));
        verify(images,times(1)).generate(argThat(r->r.referenceImageUrls().isEmpty()));
    }

    @Test void readinessGateScalesWithAssetDependencyLevel(){
        generateLook();
        approve(run(current(lookId,"FRONT")));

        assertThatCode(()->assets.requireReady(projectId,coreId,List.of(lookId),AssetDependencyAnalyzer.Level.A0))
                .doesNotThrowAnyException();
        assertThatCode(()->assets.requireReady(projectId,coreId,List.of(lookId),AssetDependencyAnalyzer.Level.A1))
                .doesNotThrowAnyException();
        assertThatThrownBy(()->assets.requireReady(projectId,coreId,List.of(lookId),AssetDependencyAnalyzer.Level.A2))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("多视图");
    }

    @Test void a1AssetPreparationSubmitsOnlyTheMasterReference(){
        ObjectNode request=obj().put("assetDependencyLevel","A1");request.putArray("assetIds").add(lookId);
        assets.generate(projectId,request);
        assertThat(store.list(ASSET_VIEW,projectId,lookId)).hasSize(4);
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(1);

        approve(run(current(lookId,"FRONT")));

        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(1);
        assertThat(text(store.get(CHARACTER_LOOK,lookId),"referenceStatus")).isEqualTo("APPROVED");
        assertThat(current(lookId,"LEFT").hasNonNull("generationJobId")).isFalse();
    }

    @Test void changedDescriptionCannotBeApprovedOrUsedAndRegenerationStartsANewMaster(){
        generateLook();ObjectNode master=run(current(lookId,"FRONT"));
        ObjectNode look=store.get(CHARACTER_LOOK,lookId);store.update(CHARACTER_LOOK,lookId,revision(look),look.deepCopy().put("description","换成红棉袄"));
        assertThatThrownBy(()->approve(master)).isInstanceOf(WorkflowException.class).hasMessageContaining("素材设定已修改");
        ObjectNode next=assets.regenerate(id(master),obj().put("revision",revision(master)));
        assertThat(next.path("setVersion").asInt()).isEqualTo(2);assertThat(next.path("master").asBoolean()).isTrue();
        assertThat(store.get(ASSET_VIEW,id(master)).path("stale").asBoolean()).isTrue();
        assertThat(next.path("sourceSnapshot").path("asset").path("description").asText()).isEqualTo("换成红棉袄");
    }

    @Test void narrativeBibleEditDoesNotInvalidateVisualReferenceHash(){
        generateLook();
        ObjectNode master=run(current(lookId,"FRONT"));
        String characterId=text(store.get(CHARACTER_LOOK,lookId),"characterId");
        ObjectNode actor=store.get(CHARACTER,characterId);
        ObjectNode changed=actor.deepCopy();
        changed.set("narrativeBible",obj().put("want","找到失踪的邻居").put("secret","曾经见过来信"));
        store.update(CHARACTER,characterId,revision(actor),changed);

        assertThatCode(()->approve(master)).doesNotThrowAnyException();
    }

    @Test void uncertainProviderRequestIsPreservedAndCannotBeRepeated(){
        generateLook();ObjectNode master=current(lookId,"FRONT");ObjectNode job=jobs.claim().orElseThrow();
        jobs.mutate(id(job),j->j.put("providerRequestId","021-test-request"));
        ObjectNode failed=jobs.fail(id(job),"TIMEOUT","请求状态不明",false,true);assets.syncFailure(failed);
        ObjectNode view=store.get(ASSET_VIEW,id(master));assertThat(text(view,"providerRequestId")).isEqualTo("021-test-request");
        assertThat(view.path("submissionUncertain").asBoolean()).isTrue();
        assertThatThrownBy(()->assets.regenerate(id(view),obj().put("revision",revision(view)))).isInstanceOf(WorkflowException.class).hasMessageContaining("核对");
        verifyNoInteractions(images);
    }

    @Test void replacingOneViewPreservesApprovedImagesAndReplacesOnlyOnePaidRequest(){
        generateLook();approve(run(current(lookId,"FRONT")));
        for(String angle:List.of("LEFT","RIGHT","BACK"))approve(run(current(lookId,angle)));
        ObjectNode previous=current(lookId,"LEFT");ObjectNode front=current(lookId,"FRONT");
        ObjectNode replacement=assets.regenerate(id(previous),obj().put("revision",revision(previous)));
        assertThat(replacement.path("setVersion").asInt()).isEqualTo(2);
        assertThat(text(current(lookId,"FRONT"),"providerUrl")).isEqualTo(text(front,"providerUrl"));
        assertThat(current(lookId,"BACK").path("referenceViewIds").get(0).asText()).isEqualTo(id(current(lookId,"FRONT")));
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(5);
        approve(run(replacement));assertThat(assets.approvedReferences(projectId,coreId,lookId)).hasSize(4);
        verify(images,times(5)).generate(any());
    }

    @Test void replacingAnAnchorInvalidatesOnlyItsDependentShotAndTimeline(){
        generateLook();ObjectNode master=run(current(lookId,"FRONT"));
        String episode=id(store.create(EPISODE,obj().put("projectId",projectId).put("name","第一集")));
        String scene=id(store.create(SCENE,obj().put("projectId",projectId).put("episodeId",episode).put("name","院内")));
        ObjectNode affected=obj().put("projectId",projectId).put("sceneId",scene).put("duration",3).put("status","PLANNED");affected.putArray("assetViewIds").add(id(master));
        ObjectNode shot=store.create(SHOT,affected),other=store.create(SHOT,obj().put("projectId",projectId).put("sceneId",scene).put("duration",3).put("status","PLANNED"));
        ObjectNode timeline=store.create(TIMELINE,obj().put("projectId",projectId).put("episodeId",episode));
        ObjectNode item=store.create(TIMELINE_ITEM,obj().put("projectId",projectId).put("timelineId",id(timeline)).put("shotId",id(shot)));
        assets.regenerate(id(master),obj().put("revision",revision(master)));
        assertThat(store.get(SHOT,id(shot)).path("assetReferencesStale").asBoolean()).isTrue();
        assertThat(store.get(SHOT,id(other)).path("assetReferencesStale").asBoolean()).isFalse();
        assertThat(store.get(TIMELINE,id(timeline)).path("assetReferencesStale").asBoolean()).isTrue();
        assertThat(store.get(TIMELINE_ITEM,id(item)).path("assetReferencesStale").asBoolean()).isTrue();
    }

    @Test void aSecondCostumeUsesTheSameApprovedActorInsteadOfGeneratingANewFace(){
        String characterId=text(store.get(CHARACTER_LOOK,lookId),"characterId");
        String secondLook=id(store.create(CHARACTER_LOOK,obj().put("projectId",projectId).put("storyBibleId",coreId).put("characterId",characterId).put("name","雨披").put("description","同一人换上旧透明雨披")));
        ObjectNode request=obj();request.putArray("assetIds").add(secondLook);assets.generate(projectId,request);
        assertThat(store.list(ASSET_VIEW,projectId,null)).hasSize(8);
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(1);
        assertThat(text(current(secondLook,"FRONT"),"status")).isEqualTo("PLANNED");
        ObjectNode base=approve(run(current(lookId,"FRONT")));
        ObjectNode costume=current(secondLook,"FRONT");assertThat(costume.path("referenceViewIds").get(0).asText()).isEqualTo(id(base));
        run(costume);
        verify(images).generate(argThat(r->r.referenceImageUrls().equals(List.of(text(base,"providerUrl")))&&r.prompt().contains("旧透明雨披")));
        assets.regenerate(id(base),obj().put("revision",revision(base)));
        assertThat(store.get(ASSET_VIEW,id(costume)).path("stale").asBoolean()).isTrue();
    }

    private void generateLook(){ObjectNode request=obj();request.putArray("assetIds").add(lookId);assets.generate(projectId,request);}
    @Test void characterSideViewsUseOpposedStructuredCameraContracts(){
        generateLook();approve(run(current(lookId,"FRONT")));
        ObjectNode left=store.get(GENERATION_JOB,required(current(lookId,"LEFT"),"generationJobId"));
        ObjectNode right=store.get(GENERATION_JOB,required(current(lookId,"RIGHT"),"generationJobId"));
        JsonNode leftCamera=left.path("inputSnapshot").path("viewCamera"),rightCamera=right.path("inputSnapshot").path("viewCamera");

        assertThat(leftCamera.path("cameraAzimuthDegrees").asInt()).isEqualTo(-90);
        assertThat(rightCamera.path("cameraAzimuthDegrees").asInt()).isEqualTo(90);
        assertThat(leftCamera.path("visibleFaceSide").asText()).isEqualTo("SUBJECT_LEFT");
        assertThat(rightCamera.path("visibleFaceSide").asText()).isEqualTo("SUBJECT_RIGHT");
        assertThat(leftCamera.path("noseScreenDirection").asText()).isEqualTo("FRAME_RIGHT");
        assertThat(rightCamera.path("noseScreenDirection").asText()).isEqualTo("FRAME_LEFT");
        assertThat(left.path("inputSnapshot").path("prompt").asText()).contains("人物保持同一世界朝向，只移动相机","左肩到右胯");
        assertThat(right.path("inputSnapshot").path("prompt").asText()).contains("人物保持同一世界朝向，只移动相机","左肩到右胯");
    }
    @Test void ambiguousLegacyCharacterTurnaroundCannotRemainProductionReady(){
        generateLook();approve(run(current(lookId,"FRONT")));
        for(String angle:List.of("LEFT","RIGHT","BACK"))approve(run(current(lookId,angle)));
        ObjectNode oldRight=current(lookId,"RIGHT");
        store.update(ASSET_VIEW,id(oldRight),revision(oldRight),oldRight.deepCopy().put("compilerVersion","4.0.0-asset-reference"));

        assertThat(assets.approvedReferences(projectId,coreId,lookId)).isEmpty();
        ObjectNode request=obj();request.putArray("assetIds").add(lookId);assets.generate(projectId,request);

        assertThat(store.get(ASSET_VIEW,id(oldRight)).path("stale").asBoolean()).isTrue();
        assertThat(current(lookId,"FRONT").path("setVersion").asInt()).isEqualTo(2);
        assertThat(text(current(lookId,"FRONT"),"status")).isEqualTo("GENERATING");
    }
    @Test void locationChildrenUseDistinctWorldCameraContractsInsteadOfCopyingLayoutProjection(){
        ObjectNode request=obj();request.putArray("assetIds").add(locationId);assets.generate(projectId,request);
        ObjectNode master=run(current(locationId,"LAYOUT"));approve(master);
        ObjectNode front=store.get(GENERATION_JOB,required(current(locationId,"FRONT"),"generationJobId"));
        ObjectNode reverse=store.get(GENERATION_JOB,required(current(locationId,"REVERSE"),"generationJobId"));
        assertThat(front.path("inputSnapshot").path("viewCamera").path("headingDegrees").asInt()).isEqualTo(180);
        assertThat(reverse.path("inputSnapshot").path("viewCamera").path("headingDegrees").asInt()).isEqualTo(0);
        assertThat(front.path("inputSnapshot").path("viewCamera").path("screenLeft").asText()).isEqualTo("东");
        assertThat(reverse.path("inputSnapshot").path("viewCamera").path("screenLeft").asText()).isEqualTo("西");
        JsonNode input=front.path("inputSnapshot"),binding=input.path("referenceBindings").get(0);
        assertThat(input.path("compilerVersion").asText()).isEqualTo("4.2.0-location-topology");
        assertThat(input.path("locationTopology").path("fixedFeatures")).hasSize(2);
        assertThat(binding.path("role").asText()).isEqualTo("LOCATION_TOPOLOGY_REFERENCE");
        assertThat(binding.path("controls")).extracting(JsonNode::asText).contains("worldTopology","surfaceAssignments","fixedFeatureGeometry","lightingAnchors");
        assertThat(binding.path("mustNotTransfer")).extracting(JsonNode::asText).doesNotContain("background");
        assertThat(input.path("prompt").asText()).contains("真实透视","离地1.5米","featureId","不得遗漏、增添、复制或换面");
    }
    @Test void legacyLocationCompilerCannotRemainProductionReady(){
        ObjectNode request=obj();request.putArray("assetIds").add(locationId);assets.generate(projectId,request);
        approve(run(current(locationId,"LAYOUT")));
        for(String angle:List.of("FRONT","REVERSE","SIDE"))approve(run(current(locationId,angle)));
        ObjectNode oldSide=current(locationId,"SIDE");
        store.update(ASSET_VIEW,id(oldSide),revision(oldSide),oldSide.deepCopy().put("compilerVersion","4.0.0-asset-reference"));

        assertThat(assets.approvedReferences(projectId,coreId,locationId)).isEmpty();
        assets.generate(projectId,request);

        assertThat(store.get(ASSET_VIEW,id(oldSide)).path("stale").asBoolean()).isTrue();
        assertThat(current(locationId,"LAYOUT").path("setVersion").asInt()).isEqualTo(2);
        assertThat(text(current(locationId,"LAYOUT"),"status")).isEqualTo("GENERATING");
    }
    private ObjectNode current(String assetId,String angle){return store.list(ASSET_VIEW,projectId,assetId).stream().filter(v->!v.path("stale").asBoolean()&&angle.equals(text(v,"view"))).findFirst().orElseThrow();}
    private ObjectNode approve(ObjectNode view){return assets.approve(id(view),obj().put("revision",revision(view)));}
    private ObjectNode run(ObjectNode view){ObjectNode job=store.get(GENERATION_JOB,text(view,"generationJobId"));
        if("QUEUED".equals(text(job,"status")))job=jobs.mutate(id(job),j->j.put("status","RUNNING").put("attempts",1));
        assets.process(job);return store.get(ASSET_VIEW,id(view));}
}
