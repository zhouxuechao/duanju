package com.yourapp.drama.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.yourapp.drama.workflow.Documents.obj;

/** Immutable boundary between generic story domain data and phase-specific creative policy. */
public record DramaRulePack(String phase,String storyType,StoryFormat storyFormat,ObjectNode resolvedPolicy) {
    public DramaRulePack {
        if(phase==null||phase.isBlank()||storyType==null||storyType.isBlank())throw new IllegalArgumentException("DramaRulePack 缺少 phase 或 storyType");
        if(storyFormat==null||resolvedPolicy==null)throw new IllegalArgumentException("DramaRulePack 缺少 StoryFormat 或规则内容");
        resolvedPolicy=resolvedPolicy.deepCopy();
    }
    public ObjectNode toJson(){
        ObjectNode value=resolvedPolicy.deepCopy();
        value.set("storyFormat",obj().put("formatId",storyFormat.formatId()).put("narrativeForm",storyFormat.narrativeForm())
            .put("presentation",storyFormat.presentation()).put("orientation",storyFormat.orientation()));
        return value;
    }
}
