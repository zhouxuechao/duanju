package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DirectorDurationPolicyTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final DirectorDurationPolicy policy=new DirectorDurationPolicy();

    @Test void fastSilentReactionCanBeEstimatedWithoutProviderMinimum(){
        ObjectNode shot=shot("SHOW_REACTION",1);ObjectNode plan=plan("FAST",2.2);ObjectNode beat=beat("HIGH");
        DirectorDurationPolicy.DurationPlan duration=policy.recommend(shot,beat,plan);
        assertThat(duration.editDuration()).isBetween(1.25,2.0);
    }
    @Test void dialogueAndInformationRevealReceiveEnoughTime(){
        ObjectNode shot=shot("REVEAL_INFORMATION",1);shot.withArray("dialogues").add(mapper.createObjectNode().put("semanticText","你父亲根本不是意外死亡。" ).put("endMs",3200));
        DirectorDurationPolicy.DurationPlan duration=policy.recommend(shot,beat("CLIMAX"),plan("TENSION_BUILD",3));
        assertThat(duration.editDuration()).isGreaterThanOrEqualTo(3.2);
    }
    @Test void authoredEightSecondShotIsNotClippedToFiveSeconds(){
        ObjectNode shot=shot("SHOW_ACTION",1).put("duration",8);
        assertThat(policy.recommend(shot,beat("HIGH"),plan("NORMAL",8)).editDuration()).isEqualTo(8);
    }
    private ObjectNode shot(String intent,int actions){ObjectNode s=mapper.createObjectNode().put("directorIntent",intent);s.putObject("performancePlan").put("actionUnits",actions);s.putArray("dialogues");return s;}
    private ObjectNode beat(String importance){return mapper.createObjectNode().put("importance",importance);}
    private ObjectNode plan(String pacing,double average){ObjectNode p=mapper.createObjectNode().put("scenePacing",pacing);p.putObject("styleProfile").put("averageShotLength",average);return p;}
}
