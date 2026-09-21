package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class PresenceLedgerTest {
 @Test void presentCharacterCannotDisappearWithoutExitOrOffscreenMark(){var m=new ObjectMapper();ArrayNode initial=m.createArrayNode();initial.addObject().put("characterId","CH01").put("presence","PRESENT").put("visibility","FOREGROUND").put("anchor","DOOR");ArrayNode shots=m.createArrayNode();var shot=shots.addObject();shot.putArray("visibleCharacterIds");shot.putArray("offscreenCharacterIds");shot.putArray("exitedCharacterIds");assertThat(new PresenceLedger().validateScene(initial,shots)).extracting(ProductionModels.Risk::code).contains("PRESENT_CHARACTER_DISAPPEARED");}
}
