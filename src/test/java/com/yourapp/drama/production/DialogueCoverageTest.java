package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class DialogueCoverageTest {
 @Test void requiredDialogueMustAppearInACoveringShot(){var m=new ObjectMapper();ArrayNode beats=m.createArrayNode();var beat=beats.addObject().put("beatId","BT01").put("required",true);beat.putArray("dialogueIds").add("DL01");ArrayNode shots=m.createArrayNode();shots.addObject().putArray("coversBeats").add("BT01");assertThat(new ScriptBeatCoverageValidator().validate(beats,shots)).extracting(ProductionModels.Risk::code).contains("DIALOGUE_COVERAGE_MISSING");}
}
