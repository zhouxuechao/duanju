package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class CurrentPositionAnchorTest {
 @Test void movementReplacesThePreviousAnchorInsteadOfCloningTheCharacter(){var m=new ObjectMapper();var state=m.createObjectNode();state.putObject("CH01").put("anchor","DOOR").put("singleInstance",true);var moved=new CurrentPositionAnchor().move(state,"CH01","TABLE");assertThat(moved.path("CH01").path("anchor").asText()).isEqualTo("TABLE");assertThat(moved.size()).isEqualTo(1);}
}
