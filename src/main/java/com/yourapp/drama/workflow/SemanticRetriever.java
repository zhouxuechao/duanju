package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public interface SemanticRetriever {
    List<ObjectNode> retrieve(String novelId,String query,int limit);
}
