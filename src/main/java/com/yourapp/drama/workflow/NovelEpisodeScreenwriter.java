package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface NovelEpisodeScreenwriter {
    String cacheIdentity();
    Result write(Input input);
    record Input(String projectId,ObjectNode novel,ObjectNode adaptationPlan,ObjectNode episodePlan,ObjectNode context,String generationContextHash){}
    record Result(ObjectNode script,String model,String requestId,String compilerVersion){}
}
