package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanaryProductionGateTest {
    @Test
    void requiresEveryProductionCapabilityFromTheAdapterContract() {
        PipelineCanaryProductionGate gate = new PipelineCanaryProductionGate();
        PipelineCanaryProductionAdapter incomplete = () -> EnumSet.complementOf(
                EnumSet.of(PipelineCanaryProductionAdapter.Capability.PROMPT_IR));

        assertThatThrownBy(() -> gate.requireReady(incomplete))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("PROMPT_IR");
    }

    @Test
    void acceptsTheCompleteProductionCapabilityContract() {
        PipelineCanaryProductionGate gate = new PipelineCanaryProductionGate();
        PipelineCanaryProductionAdapter complete = new PipelineCanaryProductionAdapter() {
            @Override public java.util.Set<Capability> capabilities(){return EnumSet.allOf(Capability.class);}
            @Override public com.fasterxml.jackson.databind.node.ObjectNode bindings(){var result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();for(var capability:Capability.values())result.put(capability.name(),"production.Service.method");return result;}
        };

        assertThat(gate.requireReady(complete)).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(PipelineCanaryProductionAdapter.Capability.class));
    }

    @Test void rejectsCapabilitiesThatHaveNoProductionServiceBinding(){
        PipelineCanaryProductionAdapter unbound=()->EnumSet.allOf(PipelineCanaryProductionAdapter.Capability.class);
        assertThatThrownBy(()->new PipelineCanaryProductionGate().requireReady(unbound))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("PIPELINE_PRODUCTION_BINDING_MISSING"));
    }
}
