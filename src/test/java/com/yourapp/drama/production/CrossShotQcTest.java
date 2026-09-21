package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class CrossShotQcTest {
 @Test void acceptedObservedEndIsAuthorityForTheNextStart(){var m=new ObjectMapper();var end=m.createObjectNode().put("locationId","LOC1").put("lighting","NIGHT_BLUE");end.putObject("characters").putObject("CH01").put("identityId","CH01").put("lookId","LOOK_A").put("pose","RIGHT_HAND_ON_DOOR").put("motionPhase","CONTACT").put("screenDirection","FRAME_RIGHT");end.putObject("props").putObject("P1").put("holder","CH01");var start=end.deepCopy();start.path("characters").path("CH01").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)start.path("characters").path("CH01")).put("lookId","LOOK_B").put("screenDirection","FRAME_LEFT");assertThat(new CrossShotQc().compare(end,start)).extracting(ProductionModels.Risk::code).contains("WARDROBE_DISCONTINUITY","SCREEN_DIRECTION_DISCONTINUITY");}
}
