package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NovelScreenplayValidatorTest {
    private final DocumentStore store=mock(DocumentStore.class);
    private final NovelScreenplayValidator validator=new NovelScreenplayValidator(store);

    @Test void blocksPlaceholderFutureLeakDirectorFieldsAndInvalidDuration(){
        entities();ObjectNode plan=plan(),valid=script();validator.validate("project-1",plan,valid);
        ObjectNode placeholder=valid.deepCopy();((ObjectNode)placeholder.path("scenes").path(0).path("dialogues").path(0)).put("spokenText","待补充");invalid(plan,placeholder);
        ObjectNode future=valid.deepCopy();((ObjectNode)future.path("scenes").path(0)).withArray("storyFactChanges").add("future-fact");invalid(plan,future);
        ObjectNode director=valid.deepCopy();((ObjectNode)director.path("scenes").path(0)).put("camera","推进");invalid(plan,director);
        ObjectNode duration=valid.deepCopy().put("estimatedDurationSec",120);invalid(plan,duration);
    }

    @Test void blocksNonCanonicalEntityAndInvalidStateHolder(){
        entities();ObjectNode plan=plan(),wrongCharacter=script();((ObjectNode)wrongCharacter.path("scenes").path(0)).withArray("characters").add("outsider");invalid(plan,wrongCharacter);
        ObjectNode wrongHolder=script();((ObjectNode)wrongHolder.path("scenes").path(0)).withArray("propStateChanges").add(obj().put("entityId","prop-1").put("holderId","outsider").put("state","HELD"));invalid(plan,wrongHolder);
    }

    private void invalid(ObjectNode plan,ObjectNode script){assertThatThrownBy(()->validator.validate("project-1",plan,script)).isInstanceOfSatisfying(WorkflowException.class,error->org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("NOVEL_SCREENPLAY_INVALID"));}
    private void entities(){when(store.get(CHARACTER,"character-1")).thenReturn(obj().put("id","character-1").put("projectId","project-1"));when(store.get(LOCATION,"location-1")).thenReturn(obj().put("id","location-1").put("projectId","project-1"));when(store.get(PROP,"prop-1")).thenReturn(obj().put("id","prop-1").put("projectId","project-1"));}
    private static ObjectNode plan(){ObjectNode plan=obj().put("estimatedDurationSec",90);plan.putArray("sourceChunkIds").add("chunk-1");plan.putArray("characters").add("character-1");plan.putArray("locations").add("location-1");plan.putArray("props").add("prop-1");plan.putArray("reservedFutureFacts").add("future-fact");return plan;}
    private static ObjectNode script(){ObjectNode script=obj().put("title","密门").put("summary","人物发现密门线索").put("openingHook","铜铃突然响起").put("endingHook","门后传来脚步").put("estimatedDurationSec",90);script.putArray("sourceRefs").add("chunk-1");ObjectNode scene=script.putArray("scenes").addObject().put("sceneKey","scene-1").put("title","旧宅").put("locationId","location-1").put("timeOfDay","夜").put("scenePurpose","找到密门").put("description","人物循铃声走到墙边").put("sourceType","ADAPTED");scene.putArray("characters").add("character-1");scene.putArray("props").add("prop-1");scene.putArray("actions").add("人物取下铜铃，露出后面的锁孔");for(String field:new String[]{"storyFactChanges","knowledgeChanges","relationshipChanges","characterStateChanges","propStateChanges","locationStateChanges"})scene.putArray(field);scene.putArray("sourceRefs").add("chunk-1");ObjectNode line=scene.putArray("dialogues").addObject().put("lineKey","line-1").put("characterId","character-1").put("semanticText","锁孔就在铜铃后面").put("spokenText","锁孔在这儿。").put("subtitleText","锁孔在这儿").put("emotion","警觉").put("intent","确认线索").put("startHint","取下铜铃后").put("sourceType","ADAPTED");line.putArray("sourceRefs").add("chunk-1");return script;}
}
