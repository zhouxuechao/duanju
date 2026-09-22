package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Converts an accepted episode contract into immutable beats before directing starts. */
public final class ScriptBeatExtractor {
    public ArrayNode extract(JsonNode episodeScript) {
        ArrayNode result=JsonNodeFactory.instance.arrayNode();int index=0;
        for(JsonNode boundary:episodeScript.path("beatBoundaries")){
            ObjectNode beat=boundary.isObject()?((ObjectNode)boundary).deepCopy():JsonNodeFactory.instance.objectNode();
            beat.put("beatId",boundary.path("beatId").asText("BT"+String.format("%02d",index+1)))
                    .put("purpose",boundary.path("purpose").asText("推进已确认剧本节拍"))
                    .put("startSec",boundary.path("startSec").asDouble()).put("endSec",boundary.path("endSec").asDouble())
                    .put("required",true).put("sourceOrder",index++);
            beat.putArray("dialogueIds");result.add(beat);
        }
        if(result.isEmpty()){double duration=episodeScript.path("targetDurationSec").asDouble(0);int count=duration>=45?3:duration>=15?2:1;for(int i=0;i<count;i++){ObjectNode beat=result.addObject().put("beatId","BT"+String.format("%02d",i+1)).put("purpose",switch(i){case 0->"建立当前场景目标与空间关系";case 1->"阻力或信息改变人物判断";default->"人物选择形成场景结果";}).put("action","按已确认剧本推进当前节拍").put("dialogue","").put("visualInformation","呈现当前节拍的可见结果").put("startSec",duration*i/count).put("endSec",duration*(i+1)/count).put("required",true).put("sourceOrder",i);beat.putArray("hookSignals");beat.putArray("dialogueIds");}}
        return result;
    }
}
