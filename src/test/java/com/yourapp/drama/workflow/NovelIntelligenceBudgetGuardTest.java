package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelIntelligenceBudgetGuardTest {
    private final NovelIntelligenceBudgetGuard guard=new NovelIntelligenceBudgetGuard();

    @Test void failsClosedForMissingNegativeAndExceededBudgets(){
        assertThatThrownBy(()->guard.validate(obj())).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("NOVEL_INTELLIGENCE_BUDGET_INVALID"));
        assertThatThrownBy(()->guard.validate(limits().put("maxRequests",-1))).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(()->guard.assertCanReserve(run().put("usedRequests",2),"CHUNK_ANALYSIS",1,1,0)).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("NOVEL_INTELLIGENCE_BUDGET_EXCEEDED"));
        assertThatThrownBy(()->guard.assertCanReserve(run().put("usedInputTokens",99),"CHUNK_ANALYSIS",2,1,0)).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(()->guard.assertCanReserve(run().put("usedOutputTokens",99),"CHUNK_ANALYSIS",1,2,0)).isInstanceOf(WorkflowException.class);
        assertThatCode(()->guard.assertCanReserve(run(),"CHUNK_ANALYSIS",10,10,0)).doesNotThrowAnyException();
    }

    @Test void enforcesPerLayerRequestBudgets(){
        var run=run();run.putObject("layerLimits").putObject("CHUNK_ANALYSIS").put("maxRequests",1);run.putObject("layerUsage").putObject("CHUNK_ANALYSIS").put("usedRequests",1);
        assertThatThrownBy(()->guard.assertCanReserve(run,"CHUNK_ANALYSIS",1,1,0)).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("NOVEL_INTELLIGENCE_BUDGET_EXCEEDED"));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode limits(){return obj().put("maxRequests",2).put("maxInputTokens",100).put("maxOutputTokens",100).put("maxEstimatedCost",0);}
    private static com.fasterxml.jackson.databind.node.ObjectNode run(){return limits().put("usedRequests",0).put("usedInputTokens",0).put("usedOutputTokens",0).put("usedEstimatedCost",0);}
}
