package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface NovelAdaptationPlanner {
    String contextHash(ObjectNode novel,ObjectNode request);
    ObjectNode plan(ObjectNode novel,ObjectNode request);
}
