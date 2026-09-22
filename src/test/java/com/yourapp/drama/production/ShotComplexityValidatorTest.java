package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShotComplexityValidatorTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ShotComplexityValidator validator=new ShotComplexityValidator();

    @Test void rejectsIdentityRevealThroughHeavyOcclusionBeforeProvider(){
        ObjectNode shot=base();
        shot.putObject("visibilityPlan").put("occlusion","HEAVY").put("requiredDetail","IDENTITY").putArray("visibleFeatures").add("完整面孔");
        assertThat(validator.validate(shot)).extracting(ProductionModels.Risk::code).contains("INFORMATION_VISIBILITY_CONFLICT");
    }

    @Test void rejectsOverloadedPerformanceAndDuration(){
        ObjectNode shot=base().put("duration",2);
        ((ObjectNode)shot.path("performancePlan")).put("actionUnits",3).putArray("propOperations").add("拿铃").add("开门");
        shot.putArray("dialogues").add(mapper.createObjectNode().put("semanticText","这段对白在两秒镜头里根本无法自然说完，还要求人物连续完成多个动作。"));
        assertThat(validator.validate(shot)).extracting(ProductionModels.Risk::code)
            .contains("TOO_MANY_ACTIONS","TOO_MANY_PROP_OPERATIONS","INSUFFICIENT_DURATION");
    }

    @Test void wideEstablishingShotMayShowAGroupWhenItDoesNotRequireIdentityDetail(){
        ObjectNode shot=base().put("shotSize","WIDE").put("directorIntent","ESTABLISH_SPACE");
        shot.withArray("characterIds").add("listener").add("keeper");
        shot.putObject("visibilityPlan").put("occlusion","NONE").put("requiredDetail","ACTION").putArray("visibleFeatures").add("三人整体站位");
        assertThat(validator.validate(shot)).extracting(ProductionModels.Risk::code).doesNotContain("TOO_MANY_VISIBLE_IDENTITIES");

        shot.put("shotSize","MEDIUM");
        assertThat(validator.validate(shot)).extracting(ProductionModels.Risk::code).contains("TOO_MANY_VISIBLE_IDENTITIES");
    }

    @Test void mediumFullClosingTableauMayShowAGroupWhenOnlyFullBodySilhouettesAreRequired(){
        ObjectNode shot=base().put("shotSize","MEDIUM_FULL").put("directorIntent","EMPHASIZE_EMOTION");
        shot.withArray("characterIds").add("listener").add("keeper");
        shot.putObject("visibilityPlan").put("occlusion","NONE").put("requiredDetail","FULL_BODY").putArray("visibleFeatures").add("三人僵直站姿与轮廓");
        assertThat(validator.validate(shot)).extracting(ProductionModels.Risk::code).doesNotContain("TOO_MANY_VISIBLE_IDENTITIES");
    }

    private ObjectNode base(){
        ObjectNode shot=mapper.createObjectNode().put("duration",3).put("shotSize","CLOSE_UP").put("cameraMovement","STATIC");
        shot.putArray("characterIds").add("actor");
        shot.putObject("blocking").put("doorWindowState","院门仅留一指宽门缝");
        ObjectNode performance=mapper.createObjectNode().put("primaryAction","老人向前探身").put("actionUnits",1);performance.putArray("propOperations");shot.set("performancePlan",performance);
        return shot;
    }
}
