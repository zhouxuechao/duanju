package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RevisionFoundationIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired DependencyRevisionService revisions;
    @Autowired ProductionInputSnapshotService snapshots;

    @Test void oneCanonicalCharacterKeepsDefinitionLookAndStoryStateTimelinesSeparate(){
        Fixture f=fixture("版本与状态");

        revisions.ensureBaseline(f.projectId());
        ObjectNode revised=revisions.recordDefinitionRevision(
                CHARACTER,f.characterId(),obj().put("name","林川").put("ageDefinition",30).put("appearanceDefinition","左眉尾浅疤"),
                ChangeType.DEFINITION_REVISION,ChangeScope.FROM_CURRENT_POINT,
                new ChangePoint(null,20,null,null,200d,null),"年龄设定纠正","HUMAN");
        ObjectNode look2=revisions.recordCharacterLookVersion(f.characterId(),obj().put("name","升职西装").put("hair","短发").put("wardrobe","深灰西装"),
                new ChangePoint(null,11,null,null,110d,null),"剧情换装","HUMAN");
        ObjectNode ep19=episode(f.projectId(),19),ep20=episode(f.projectId(),20);revisions.registerDependency(f.projectId(),CHARACTER,f.characterId(),EPISODE,id(ep19),"CHARACTER_STATE",new ChangePoint(id(ep19),19,null,null,190d,null));revisions.registerDependency(f.projectId(),CHARACTER,f.characterId(),EPISODE,id(ep20),"CHARACTER_STATE",new ChangePoint(id(ep20),20,null,null,200d,null));
        ObjectNode stateChange=revisions.recordStoryStateTransition(CHARACTER,f.characterId(),obj().put("state","升职").put("alive",true).put("occupation","部门主管"),
                new ChangePoint(null,20,null,null,200d,null),"升职");

        assertThat(store.list(CHARACTER,f.projectId(),null)).hasSize(1);
        assertThat(store.list(CHARACTER_DEFINITION_VERSION,f.projectId(),f.characterId())).hasSize(2);
        assertThat(store.list(CHARACTER_LOOK,f.projectId(),f.characterId())).hasSize(2);
        assertThat(revisions.resolveDefinition(CHARACTER,f.characterId(),new ChangePoint(null,10,null,null,100d,null)).path("ageDefinition").asInt()).isEqualTo(28);
        assertThat(revisions.resolveDefinition(CHARACTER,f.characterId(),new ChangePoint(null,20,null,null,200d,null)).path("ageDefinition").asInt()).isEqualTo(30);
        assertThat(revisions.resolveCharacterLook(f.characterId(),100).path("version").asInt()).isEqualTo(1);
        assertThat(revisions.resolveCharacterLook(f.characterId(),110).path("id").asText()).isEqualTo(id(look2));
        assertThat(revised.path("changePoint").path("episodeNo").asInt()).isEqualTo(20);
        assertThat(store.list(CHARACTER_STATE,f.projectId(),f.characterId())).singleElement().satisfies(state->assertThat(state.path("changeType").asText()).isEqualTo("STORY_STATE_TRANSITION"));
        assertThat(stateChange.path("affectedNodes").findValuesAsText("resourceId")).containsExactly(id(ep20));assertThat(store.get(EPISODE,id(ep19)).path("stale").asBoolean()).isFalse();
    }

    @Test void forwardDependencyMarksOnlyActualFutureDependentsAndPreservesHistory(){
        Fixture f=fixture("向后依赖");revisions.ensureBaseline(f.projectId());
        ObjectNode ep19=episode(f.projectId(),19),ep21=episode(f.projectId(),21),ep22=episode(f.projectId(),22),ep23=episode(f.projectId(),23);
        revisions.registerDependency(f.projectId(),CHARACTER,f.characterId(),EPISODE,id(ep21),"CHARACTER_DEFINITION",new ChangePoint(id(ep21),21,null,null,210d,null));
        revisions.registerDependency(f.projectId(),CHARACTER,f.characterId(),EPISODE,id(ep23),"CHARACTER_DEFINITION",new ChangePoint(id(ep23),23,null,null,230d,null));

        ObjectNode result=revisions.recordDefinitionRevision(
                CHARACTER,f.characterId(),obj().put("name","林川").put("ageDefinition",38).put("appearanceDefinition","左眉尾浅疤"),
                ChangeType.DEFINITION_REVISION,ChangeScope.FROM_CURRENT_POINT,
                new ChangePoint(null,20,null,null,200d,null),"人工纠正","HUMAN");

        assertThat(result.path("affectedNodes").findValuesAsText("resourceId")).containsExactlyInAnyOrder(id(ep21),id(ep23));
        assertThat(store.list(REVALIDATION_MARKER,f.projectId(),null)).extracting(node->text(node,"resourceId")).containsExactlyInAnyOrder(id(ep21),id(ep23));
        assertThat(store.get(EPISODE,id(ep19)).path("stale").asBoolean()).isFalse();
        assertThat(store.get(EPISODE,id(ep22)).path("stale").asBoolean()).isFalse();
        assertThat(store.list(EPISODE,f.projectId(),null)).hasSize(4);
    }

    @Test void firstAppearanceCorrectionStartsAtTheEarliestKnownDependency(){
        Fixture f=fixture("首次登场修正");ObjectNode character=store.create(CHARACTER,obj().put("projectId",f.projectId()).put("name","顾岚"));ObjectNode ep5=episode(f.projectId(),5),ep40=episode(f.projectId(),40);ObjectNode scene5=store.create(SCENE,obj().put("projectId",f.projectId()).put("episodeId",id(ep5)).put("name","初见")),scene40=store.create(SCENE,obj().put("projectId",f.projectId()).put("episodeId",id(ep40)).put("name","重逢"));revisions.ensureBaseline(f.projectId());
        ObjectNode early=revisions.registerDependency(f.projectId(),CHARACTER,id(character),SCENE,id(scene5),"CHARACTER_DEFINITION",new ChangePoint(id(ep5),5,id(scene5),1,5d,null));
        revisions.registerDependency(f.projectId(),CHARACTER,id(character),SCENE,id(scene40),"CHARACTER_DEFINITION",new ChangePoint(id(ep40),40,id(scene40),1,40d,null));

        ObjectNode result=revisions.recordDefinitionRevision(CHARACTER,id(character),obj().put("name","顾岚").put("identity","右眉尾旧疤"),ChangeType.DEFINITION_REVISION,ChangeScope.FROM_FIRST_APPEARANCE,new ChangePoint(null,37,null,1,37d,null),"修正首次登场身份","HUMAN");

        assertThat(result.path("version").asInt()).isEqualTo(2);assertThat(result.path("effectiveFromEpisode").asInt()).isEqualTo(5);assertThat(result.path("affectedNodes").toString()).contains(text(early,"targetId"));assertThat(store.list(REVALIDATION_MARKER,f.projectId(),null)).hasSize(2);
    }

    @Test void propAndLocationStoryTransitionsKeepCanonicalEntitiesAndClosePreviousIntervals(){
        Fixture f=fixture("道具地点状态");ObjectNode holderB=store.create(CHARACTER,obj().put("projectId",f.projectId()).put("name","乙").put("identity","乙本人"));
        revisions.ensureBaseline(f.projectId());
        revisions.recordStoryStateTransition(PROP,f.propId(),obj().put("state","完好").put("condition","INTACT").put("visible",true).put("holderCharacterId",f.characterId()),new ChangePoint(null,10,null,null,100d,null),"首次持有");
        revisions.recordStoryStateTransition(PROP,f.propId(),obj().put("state","摔裂").put("condition","CRACKED").put("visible",true).put("holderCharacterId",id(holderB)),new ChangePoint(null,15,null,null,150d,null),"转交并摔裂");
        revisions.recordStoryStateTransition(LOCATION,f.locationId(),obj().put("state","火灾后损毁").put("condition","DAMAGED"),new ChangePoint(null,31,null,null,310d,null),"火灾结果");

        List<ObjectNode> propStates=store.list(PROP_STATE,f.projectId(),f.propId());
        assertThat(store.list(PROP,f.projectId(),null)).hasSize(1);
        assertThat(propStates).hasSize(2);
        assertThat(propStates.getFirst().path("validToStoryTime").asDouble()).isEqualTo(150);
        assertThat(propStates.getLast().path("holderCharacterId").asText()).isEqualTo(id(holderB));
        assertThat(store.list(LOCATION,f.projectId(),null)).hasSize(1);
        assertThat(store.list(LOCATION_STATE,f.projectId(),f.locationId())).hasSize(1);
    }

    @Test void productionSnapshotFreezesVersionHashesAndMediaCanTraceBackWithoutDeletingOldResults(){
        Fixture f=fixture("生产快照");revisions.ensureBaseline(f.projectId());
        ObjectNode episode=episode(f.projectId(),1);
        ObjectNode scene=store.create(SCENE,obj().put("projectId",f.projectId()).put("episodeId",id(episode)).put("name","客厅"));
        ObjectNode shot=store.create(SHOT,obj().put("projectId",f.projectId()).put("sceneId",id(scene)).put("action","林川举起手机").put("purpose","展示证据").put("duration",3));
        ObjectNode prompt=store.create(PROMPT_VERSION,obj().put("projectId",f.projectId()).put("shotId",id(shot)).put("version",1).put("prompt","举起手机"));
        ObjectNode input=obj().put("promptVersionId",id(prompt)).put("scriptVersionId",id(episode)).put("continuitySnapshotHash","continuity-v1");
        input.putArray("characterIds").add(f.characterId());input.putArray("characterLookIds").add(f.lookId());input.putArray("locationIds").add(f.locationId());input.putArray("propIds").add(f.propId());
        ObjectNode snapshot=snapshots.freeze(f.projectId(),id(shot),"KEYFRAME",input);
        ObjectNode keyframe=store.create(KEYFRAME,obj().put("projectId",f.projectId()).put("shotId",id(shot)).put("version",1).put("provider","MOCK").put("providerUrl","https://media.example/frame.png").put("promptVersionId",id(prompt)).put("productionInputSnapshotId",id(snapshot)).put("assetSnapshotHash",text(snapshot,"assetSnapshotHash")));
        ObjectNode take=store.create(VIDEO_TAKE,obj().put("projectId",f.projectId()).put("shotId",id(shot)).put("takeNo",1).put("sourceKeyframeId",id(keyframe)).put("sourceProviderUrlSnapshot","https://media.example/frame.png").put("promptVersionId",id(prompt)).put("productionInputSnapshotId",id(snapshot)).put("assetSnapshotHash",text(snapshot,"assetSnapshotHash")));
        ObjectNode rendered=store.create(TIMELINE,obj().put("projectId",f.projectId()).put("episodeId",id(episode)).put("name","已发布成片").put("finalUrl","/api/media/renders/old.mp4").put("productionInputSnapshotId",id(snapshot)).put("assetSnapshotHash",text(snapshot,"assetSnapshotHash")));

        ObjectNode trace=snapshots.trace(VIDEO_TAKE,id(take));
        assertThat(text(snapshot,"assetSnapshotHash")).hasSize(64);
        assertThat(snapshot.path("characterDefinitionVersionIds")).hasSize(1);
        assertThat(snapshot.path("characterLookVersionIds")).hasSize(1);
        assertThat(snapshot.path("locationDefinitionVersionIds")).hasSize(1);
        assertThat(snapshot.path("propDefinitionVersionIds")).hasSize(1);
        assertThat(trace.path("promptVersionId").asText()).isEqualTo(id(prompt));
        assertThat(trace.path("scriptVersionId").asText()).isEqualTo(id(episode));
        assertThat(trace.path("continuitySnapshotHash").asText()).isEqualTo("continuity-v1");
        assertThat(trace.path("characterDefinitionVersionIds")).hasSize(1);assertThat(trace.path("characterLookVersionIds")).hasSize(1);assertThat(trace.path("locationDefinitionVersionIds")).hasSize(1);assertThat(trace.path("propDefinitionVersionIds")).hasSize(1);
        revisions.recordDefinitionRevision(PROP,f.propId(),obj().put("name","银色手机").put("appearanceDefinition","银色金属后盖"),ChangeType.DEFINITION_REVISION,ChangeScope.FROM_CURRENT_POINT,new ChangePoint(null,2,null,null,20d,null),"颜色修正","HUMAN");
        assertThat(store.get(KEYFRAME,id(keyframe))).isNotNull();
        assertThat(store.get(VIDEO_TAKE,id(take))).isNotNull();
        assertThat(text(store.get(TIMELINE,id(rendered)),"finalUrl")).isEqualTo("/api/media/renders/old.mp4");
        assertThat(snapshots.trace(TIMELINE,id(rendered)).path("assetSnapshotHash").asText()).isEqualTo(text(snapshot,"assetSnapshotHash"));
        assertThat(snapshots.trace(VIDEO_TAKE,id(take)).path("assetSnapshotHash").asText()).isEqualTo(text(snapshot,"assetSnapshotHash"));
    }

    private Fixture fixture(String name){
        ObjectNode project=store.create(PROJECT,obj().put("name",name).put("idea","N0 fixture"));String projectId=id(project);
        ObjectNode character=store.create(CHARACTER,obj().put("projectId",projectId).put("name","林川").put("identity","调查员").put("ageDefinition",28).put("appearanceDefinition","左眉尾浅疤"));
        ObjectNode look=store.create(CHARACTER_LOOK,obj().put("projectId",projectId).put("characterId",id(character)).put("name","便装").put("hair","短发").put("wardrobe","深色夹克").put("validFromStoryTime",0));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",projectId).put("name","客厅").put("appearanceDefinition","东墙有窗，西墙有门"));
        ObjectNode prop=store.create(PROP,obj().put("projectId",projectId).put("name","手机").put("appearanceDefinition","黑色直板手机"));
        return new Fixture(projectId,id(character),id(look),id(location),id(prop));
    }
    private ObjectNode episode(String projectId,int number){return store.create(EPISODE,obj().put("projectId",projectId).put("name","第"+number+"集").put("episodeNo",number));}
    private record Fixture(String projectId,String characterId,String lookId,String locationId,String propId){}
}
