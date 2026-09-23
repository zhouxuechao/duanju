package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.yourapp.drama.workflow.Documents.obj;

public record ChangePoint(String episodeId,Integer episodeNo,String sceneId,Integer sceneNo,Double storyTime,String scriptVersionId) {
    public ObjectNode toJson() {
        ObjectNode value=obj();
        if(episodeId!=null&&!episodeId.isBlank())value.put("episodeId",episodeId);
        if(episodeNo!=null)value.put("episodeNo",episodeNo);
        if(sceneId!=null&&!sceneId.isBlank())value.put("sceneId",sceneId);
        if(sceneNo!=null)value.put("sceneNo",sceneNo);
        if(storyTime!=null&&Double.isFinite(storyTime))value.put("storyTime",storyTime);
        if(scriptVersionId!=null&&!scriptVersionId.isBlank())value.put("scriptVersionId",scriptVersionId);
        return value;
    }
    public static ChangePoint origin(){return new ChangePoint(null,0,null,0,null,null);}
}
