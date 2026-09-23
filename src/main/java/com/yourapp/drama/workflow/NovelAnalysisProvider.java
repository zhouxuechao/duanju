package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Chunk-scoped structured analysis boundary. Implementations must never receive a whole novel. */
public interface NovelAnalysisProvider {
    ObjectNode analyze(ObjectNode chunkRequest);
}
