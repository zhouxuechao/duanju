package com.yourapp.drama.domain;

/** Orthogonal delivery format; genre and story engine remain in StoryProfile/DramaRulePack. */
public record StoryFormat(String formatId,String narrativeForm,String presentation,String orientation) {
    public StoryFormat {
        if(formatId==null||formatId.isBlank())throw new IllegalArgumentException("storyFormat.formatId 不能为空");
        if(narrativeForm==null||narrativeForm.isBlank())throw new IllegalArgumentException("storyFormat.narrativeForm 不能为空");
        if(presentation==null||presentation.isBlank())throw new IllegalArgumentException("storyFormat.presentation 不能为空");
        if(orientation==null||orientation.isBlank())throw new IllegalArgumentException("storyFormat.orientation 不能为空");
    }
}
