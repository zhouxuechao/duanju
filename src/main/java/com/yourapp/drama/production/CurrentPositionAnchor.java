package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.node.ObjectNode;
public final class CurrentPositionAnchor {
 public ObjectNode move(ObjectNode positions,String characterId,String destination){ObjectNode next=positions.deepCopy();ObjectNode state=next.withObject(characterId);state.put("anchor",destination).put("singleInstance",true);return next;}
}
