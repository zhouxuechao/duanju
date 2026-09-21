package com.yourapp.drama.workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class PremiseGateTest {
 @Test void failedPremiseBlocksUntilAnAuditableDecision(){var m=new ObjectMapper();var premise=m.createObjectNode().put("viable",false).put("capacityRisk","单一冲突无法支撑80集");var gate=new PremiseGate();assertThat(gate.evaluate(premise).decision()).isEqualTo(PremiseGate.Decision.BLOCK_REVIEW);assertThatThrownBy(()->gate.override("","tester")).hasMessageContaining("原因");assertThat(gate.override("接受分单元改造后的容量风险","tester").decision()).isEqualTo(PremiseGate.Decision.OVERRIDE);}
}
