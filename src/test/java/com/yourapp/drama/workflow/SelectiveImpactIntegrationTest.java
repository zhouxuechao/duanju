package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class SelectiveImpactIntegrationTest {
    @Autowired DocumentStore store;@Autowired ScriptWorkspaceService scripts;@Autowired ScriptImpactAnalyzer impacts;@Autowired SelectiveRebuildService rebuilds;@Autowired DependencyRevisionService dependencies;
    @Autowired PlatformReviewService platformReviews;

    @Test void dialogueOnlyAndForwardContinuityProduceSelectiveNonExecutingPlans(){
        ObjectNode project=store.create(PROJECT,obj().put("name","影响分析").put("sourceMode","IDEA").put("scriptWorkspaceRequired",true));String projectId=id(project);
        ObjectNode episode1=store.create(EPISODE,obj().put("projectId",projectId).put("episodeNo",20).put("name","秘密"));ObjectNode episode2=store.create(EPISODE,obj().put("projectId",projectId).put("episodeNo",21).put("name","利用秘密"));ObjectNode episode3=store.create(EPISODE,obj().put("projectId",projectId).put("episodeNo",22).put("name","无关日常"));
        ObjectNode v1=scripts.createReviewVersion(id(episode1),script("我知道了",false),"IDEA","AI",obj());v1=scripts.confirm(id(v1),obj().put("revision",revision(v1)));
        ObjectNode v2=scripts.fork(id(episode1),obj().put("revision",revision(v1)).put("createdBy","USER"));ObjectNode edit=v2.deepCopy();edit.set("structuredContent",script("行，我已经知道了",false));v2=scripts.update(id(v2),edit);
        long jobsBefore=store.list(GENERATION_JOB,projectId,null).size();ObjectNode dialogue=impacts.analyze(id(v1),id(v2),ChangeScope.FROM_CURRENT_POINT,new ChangePoint(id(episode1),20,null,1,20d,id(v2)));
        ObjectNode rebuild=rebuilds.prepare(id(dialogue));
        assertThat(text(dialogue,"impactType")).isEqualTo("DIALOGUE_ONLY");assertThat(dialogue.path("affectedKeyframes")).isEmpty();assertThat(dialogue.path("affectedVideos")).isEmpty();assertThat(dialogue.path("affectedAudio").toString()).contains("REBUILD_TTS");assertThat(text(rebuild,"status")).isEqualTo("PLANNED");assertThat(rebuild.path("requiresUserConfirmation").asBoolean()).isTrue();assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize((int)jobsBefore);

        ObjectNode v3=scripts.fork(id(episode1),obj().put("revision",revision(v2)).put("createdBy","USER"));ObjectNode continuity=v3.deepCopy();continuity.set("structuredContent",script("行，我已经知道了",true));v3=scripts.update(id(v3),continuity);
        dependencies.registerDependency(projectId,EPISODE_SCRIPT_VERSION,id(v3),EPISODE,id(episode2),"KNOWLEDGE",new ChangePoint(id(episode2),21,null,1,21d,id(v3)));
        dependencies.registerDependency(projectId,EPISODE_SCRIPT_VERSION,id(v3),EPISODE,id(episode3),"UNRELATED",new ChangePoint(id(episode3),22,null,1,22d,id(v3)));
        ObjectNode forward=impacts.analyze(id(v2),id(v3),ChangeScope.FROM_CURRENT_POINT,new ChangePoint(id(episode1),20,null,1,20d,id(v3)));
        assertThat(text(forward,"impactType")).isEqualTo("CONTINUITY_FORWARD");assertThat(forward.path("affectedEpisodes").toString()).contains(id(episode2)).doesNotContain(id(episode3));assertThat(store.get(EPISODE,id(episode1)).path("stale").asBoolean()).isFalse();
    }

    @Test void platformRejectionForksFixWithoutProviderWork(){
        ObjectNode project=store.create(PROJECT,obj().put("name","审核退回").put("sourceMode","IDEA")),episode=store.create(EPISODE,obj().put("projectId",id(project)).put("episodeNo",37).put("name","退回集"));
        ObjectNode version=scripts.createReviewVersion(id(episode),script("原对白",false),"IDEA","AI",obj());version=scripts.confirm(id(version),obj().put("revision",revision(version)));
        ObjectNode issue=platformReviews.reject(obj().put("episodeId",id(episode)).put("scriptVersionId",id(version)).put("platform","TEST_PLATFORM").put("reasonCode","DIALOGUE_COMPLIANCE").put("reasonText","对白需调整"));
        ObjectNode fix=store.get(EPISODE_SCRIPT_VERSION,text(issue,"resolutionVersionId"));
        assertThat(text(issue,"status")).isEqualTo("IN_FIX");assertThat(text(fix,"status")).isEqualTo("DRAFT");assertThat(text(fix,"changeReason")).isEqualTo("PLATFORM_REJECTION_FIX");assertThat(store.list(GENERATION_JOB,id(project),null)).isEmpty();
    }

    private ObjectNode script(String dialogue,boolean knowledge){ObjectNode value=obj().put("title","秘密").put("summary","知识边界").put("openingHook","一封信").put("endingHook","B尚未知道真相"),scene=obj().put("sceneKey","S1").put("title","客厅").put("description","两人交谈");scene.putArray("actions").add("A递出信封");scene.putArray("dialogues").add(obj().put("lineKey","D1").put("character","A").put("text",dialogue));if(knowledge)scene.putArray("knowledgeChanges").add(obj().put("characterKey","B").put("factKey","SECRET").put("state","UNKNOWN"));value.putArray("scenes").add(scene);return value;}
}
