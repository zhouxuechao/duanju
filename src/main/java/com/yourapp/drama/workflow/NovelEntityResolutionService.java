package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class NovelEntityResolutionService {
    private static final double AUTO_RESOLVE_THRESHOLD=.80;
    private final DocumentStore store;
    public NovelEntityResolutionService(DocumentStore store){this.store=store;}
    public ObjectNode resolve(String projectId,String novelId,String entityKind,String canonicalName,List<String> aliases,double confidence,List<String> sourceRefs){ResourceKind kind=switch(entityKind){case "CHARACTER"->CHARACTER;case "LOCATION"->LOCATION;case "PROP"->PROP;default->throw new IllegalArgumentException("entityKind 只支持 CHARACTER、LOCATION、PROP");};ObjectNode candidate=obj().put("projectId",projectId).put("novelId",novelId).put("entityKind",entityKind).put("canonicalName",canonicalName).put("confidence",confidence).put("status",confidence<AUTO_RESOLVE_THRESHOLD?"ENTITY_REVIEW_REQUIRED":"RESOLVED");ArrayNode refs=candidate.putArray("sourceRefs");sourceRefs.forEach(refs::add);ArrayNode names=candidate.putArray("aliases");aliases.forEach(names::add);candidate=store.create(ENTITY_CANDIDATE,candidate);if(confidence<AUTO_RESOLVE_THRESHOLD)return candidate;ObjectNode entity=store.list(kind,projectId,null).stream().filter(item->canonicalName.equals(text(item,"name"))).findFirst().orElseGet(()->{ObjectNode value=obj().put("projectId",projectId).put("name",canonicalName).put("source","NOVEL").put("novelId",novelId);if(kind==CHARACTER)value.put("identityLocked",false);return store.create(kind,value);});for(String alias:aliases)if(store.list(ENTITY_ALIAS,projectId,null).stream().noneMatch(item->alias.equals(text(item,"alias"))&&id(entity).equals(text(item,"entityId")))){ObjectNode value=obj().put("projectId",projectId).put("entityId",id(entity)).put("alias",alias).put("aliasType","NOVEL_ALIAS").put("source","NOVEL").put("confidence",confidence).put("entityKind",entityKind);ArrayNode aliasRefs=value.putArray("sourceRefs");sourceRefs.forEach(aliasRefs::add);store.create(ENTITY_ALIAS,value);}ObjectNode saved=store.get(ENTITY_CANDIDATE,id(candidate));return store.update(ENTITY_CANDIDATE,id(saved),revision(saved),saved.deepCopy().put("canonicalEntityId",id(entity)).put("status","RESOLVED"));}
    public ObjectNode confirm(String candidateId){ObjectNode candidate=store.get(ENTITY_CANDIDATE,candidateId);if(!"ENTITY_REVIEW_REQUIRED".equals(text(candidate,"status")))return candidate;List<String> aliases=new ArrayList<>(),refs=new ArrayList<>();candidate.path("aliases").forEach(value->aliases.add(value.asText()));candidate.path("sourceRefs").forEach(value->refs.add(value.asText()));ObjectNode resolved=resolve(project(candidate),required(candidate,"novelId"),required(candidate,"entityKind"),required(candidate,"canonicalName"),aliases,1,refs);ObjectNode current=store.get(ENTITY_CANDIDATE,candidateId);return store.update(ENTITY_CANDIDATE,candidateId,revision(current),current.deepCopy().put("status","RESOLVED").put("canonicalEntityId",text(resolved,"canonicalEntityId")).put("reviewedBy","HUMAN"));}
}
