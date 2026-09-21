package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public final class PresenceLedger {
 public List<ProductionModels.Risk> validateScene(JsonNode initial,JsonNode shots){List<ProductionModels.Risk> risks=new ArrayList<>();Map<String,String> presence=new LinkedHashMap<>();for(JsonNode e:initial)presence.put(text(e,"characterId"),text(e,"presence"));int i=0;for(JsonNode shot:shots){JsonNode visibleNode=shot.has("visibleCharacterIds")?shot.path("visibleCharacterIds"):shot.path("characterIds");Set<String> visible=set(visibleNode),offscreen=set(shot.path("offscreenCharacterIds")),exited=set(shot.path("exitedCharacterIds"));for(String id:new ArrayList<>(presence.keySet()))if("PRESENT".equals(presence.get(id))&&!visible.contains(id)&&!offscreen.contains(id)&&!exited.contains(id))risks.add(new ProductionModels.Risk("PRESENT_CHARACTER_DISAPPEARED","ERROR","shots["+i+"]","在场人物没有入画，也没有退场或画外说明："+id));for(String id:exited)presence.put(id,"EXIT");for(String id:offscreen)presence.put(id,"OFFSCREEN");for(String id:visible)presence.put(id,"PRESENT");i++;}return List.copyOf(risks);}
 private static Set<String> set(JsonNode n){Set<String>s=new LinkedHashSet<>();if(n.isArray())n.forEach(v->s.add(v.asText()));return s;}private static String text(JsonNode n,String f){return n.path(f).asText("").trim();}
}
