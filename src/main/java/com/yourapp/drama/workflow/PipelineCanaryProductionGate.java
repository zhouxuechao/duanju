package com.yourapp.drama.workflow;

import java.util.EnumSet;
import java.util.Set;

/** Fails closed unless the selected adapter exposes every production stage. */
public final class PipelineCanaryProductionGate {
    public Set<PipelineCanaryProductionAdapter.Capability> requireReady(PipelineCanaryProductionAdapter adapter) {
        if (adapter == null) throw new WorkflowException("PIPELINE_PRODUCTION_ADAPTER_MISSING", "Pipeline Canary production adapter is unavailable");
        EnumSet<PipelineCanaryProductionAdapter.Capability> actual = adapter.capabilities().isEmpty()
                ? EnumSet.noneOf(PipelineCanaryProductionAdapter.Capability.class)
                : EnumSet.copyOf(adapter.capabilities());
        EnumSet<PipelineCanaryProductionAdapter.Capability> missing = EnumSet.allOf(PipelineCanaryProductionAdapter.Capability.class);
        missing.removeAll(actual);
        if (!missing.isEmpty()) throw new WorkflowException("PIPELINE_PRODUCTION_NOT_READY", "Pipeline Canary production capabilities missing: " + missing);
        var bindings=adapter.bindings();
        for(var capability:PipelineCanaryProductionAdapter.Capability.values())
            if(bindings.path(capability.name()).asText().isBlank())throw new WorkflowException("PIPELINE_PRODUCTION_BINDING_MISSING","Pipeline Canary production binding missing: "+capability);
        return Set.copyOf(actual);
    }
}
