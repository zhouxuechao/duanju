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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class ScriptWorkspaceSafetyTest {
    @Autowired DocumentStore store;@Autowired ScriptWorkspaceService scripts;

    @Test void draftGateHumanLockRollbackAndStaleVersionAreEnforced(){
        ObjectNode project=store.create(PROJECT,obj().put("name","安全门").put("sourceMode","IDEA").put("scriptWorkspaceRequired",true));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("episodeNo",1).put("name","第一集"));
        ObjectNode structured=script("原对白","AI_GENERATED");ObjectNode v1=scripts.createReviewVersion(id(episode),structured,"IDEA","AI",obj());
        assertThatThrownBy(()->scripts.requireProductionReady(id(episode),id(v1))).isInstanceOf(WorkflowException.class).hasMessageContaining("确认");
        ObjectNode confirmed=scripts.confirm(id(v1),obj().put("revision",revision(v1)));assertThat(scripts.requireProductionReady(id(episode),id(confirmed))).isNotNull();
        ObjectNode draft=scripts.fork(id(episode),obj().put("revision",revision(confirmed)).put("createdBy","USER"));ObjectNode edit=draft.deepCopy();edit.set("structuredContent",script("人工锁定对白","HUMAN_LOCKED"));draft=scripts.update(id(draft),edit);
        ObjectNode changed=script("AI 想覆盖","AI_GENERATED");ObjectNode locked=draft;
        assertThatThrownBy(()->scripts.updateByAi(id(locked),changed)).isInstanceOf(WorkflowException.class).hasMessageContaining("人工锁定");
        assertThatThrownBy(()->scripts.requireProductionReady(id(episode),id(locked))).isInstanceOf(WorkflowException.class).hasMessageContaining("过期");
        ObjectNode rollback=scripts.rollback(id(confirmed),obj().put("createdBy","USER"));assertThat(text(rollback,"status")).isEqualTo("DRAFT");assertThat(text(rollback.path("structuredContent").path("scenes").get(0).path("dialogues").get(0),"text")).isEqualTo("原对白");
    }

    private ObjectNode script(String line,String ownership){ObjectNode value=obj().put("title","第一集").put("summary","测试").put("openingHook","开门").put("endingHook","敲门"),scene=obj().put("sceneKey","S1");scene.putArray("actions").add("开门");scene.putArray("dialogues").add(obj().put("lineKey","D1").put("text",line).put("ownership",ownership));value.putArray("scenes").add(scene);return value;}
}
