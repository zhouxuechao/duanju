package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.JsonNode;
/** Selects an accepted take's observed state as the next-shot authority. */
public final class ObservedStateAuthority {
 public JsonNode nextStart(JsonNode acceptedPreviousTake,JsonNode plannedFallback){JsonNode observed=acceptedPreviousTake.path("observedState").path("end");if(!observed.isObject()||observed.isEmpty())observed=acceptedPreviousTake.path("observedEndState");return observed.isObject()&&!observed.isEmpty()?observed.deepCopy():plannedFallback.deepCopy();}
}
