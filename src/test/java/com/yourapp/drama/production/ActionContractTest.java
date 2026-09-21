package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ActionContractTest {
 @Test void complexActionRequiresStartContactConsequenceAndEndpoint(){var m=new ObjectMapper();var action=m.createObjectNode().put("start","抬手").put("action","推门").put("contact","").put("consequence","").put("endpoint","门开到一半");assertThat(new ActionContractValidator().validate(action,"门开到一半")).extracting(ProductionModels.Risk::code).contains("ACTION_CONTACT_MISSING","ACTION_CONSEQUENCE_MISSING");}
}
