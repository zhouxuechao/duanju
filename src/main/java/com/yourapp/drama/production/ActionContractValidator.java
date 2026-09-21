package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public final class ActionContractValidator {
 public List<ProductionModels.Risk> validate(JsonNode action,String plannedEndState){List<ProductionModels.Risk> risks=new ArrayList<>();for(String field:List.of("start","action","contact","consequence","endpoint"))if(action.path(field).asText("").isBlank())risks.add(new ProductionModels.Risk("ACTION_"+field.toUpperCase()+"_MISSING","ERROR","actionContract."+field,"复杂动作缺少 "+field));String endpoint=action.path("endpoint").asText("");if(plannedEndState!=null&&!plannedEndState.isBlank()&&!endpoint.isBlank()&&!plannedEndState.contains(endpoint)&&!endpoint.contains(plannedEndState))risks.add(new ProductionModels.Risk("ACTION_ENDPOINT_STATE_MISMATCH","ERROR","actionContract.endpoint","动作终点无法映射到计划结束状态"));return List.copyOf(risks);}
}
