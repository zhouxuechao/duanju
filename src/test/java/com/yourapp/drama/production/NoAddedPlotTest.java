package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class NoAddedPlotTest {
 @Test void directorCannotAddUnknownBeatOrStoryFact(){var m=new ObjectMapper();ArrayNode beats=m.createArrayNode();beats.addObject().put("beatId","BT01").put("required",true);ArrayNode shots=m.createArrayNode();var shot=shots.addObject();shot.putArray("coversBeats").add("BT99");shot.putArray("addedStoryFacts").add("FUTURE_SECRET");assertThat(new ScriptBeatCoverageValidator().validate(beats,shots)).extracting(ProductionModels.Risk::code).contains("DIRECTOR_ADDED_BEAT","DIRECTOR_ADDED_PLOT");}
}
