package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class FinalCreativeQualityServiceTest {
    @Test
    void classifiesBlockingWarningsAndTimecodedRepairsWithoutEditingTheTimeline() {
        var input=obj();
        for(String metric:List.of("characterConsistency","wardrobeConsistency","propContinuity","actionContinuity","screenDirectionConsistency","spatialConsistency","lightingConsistency","editingRhythm","dialogueQuality","bgmFit","sfxAccuracy","subtitleAccuracy","hook","midHook","cliffhanger"))input.put(metric,5);
        input.put("propContinuity",2).put("editingRhythm",3);
        input.putArray("issues").add(obj().put("severity","BLOCKING").put("code","PROP_DISAPPEARS")
            .put("message","铜铃在接镜处消失").put("startMs",4200).put("endMs",4700).put("repair","重做第二镜起始段"));

        var result=new FinalCreativeQualityService().evaluate(input);

        assertThat(result.path("passed").asBoolean()).isFalse();
        assertThat(result.path("BLOCKING").toString()).contains("propContinuity","PROP_DISAPPEARS");
        assertThat(result.path("WARNING").toString()).contains("editingRhythm");
        assertThat(result.path("RepairPlan").toString()).contains("重做第二镜起始段");
        assertThat(result.path("timecodes").toString()).contains("4200","4700");
    }
}
