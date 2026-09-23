package com.yourapp.drama.workflow;

import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NovelIntelligenceProviderGate {
    private final VolcengineProperties properties;private final String mode;private final boolean liveEnabled;
    public NovelIntelligenceProviderGate(VolcengineProperties properties,@Value("${drama.novel.intelligence.mode:deterministic}")String mode,@Value("${drama.novel.intelligence.live-enabled:false}")boolean liveEnabled){this.properties=properties;this.mode=mode;this.liveEnabled=liveEnabled;}
    public void validate(String role){if(!"llm".equalsIgnoreCase(mode))return;if(!liveEnabled)throw new WorkflowException("LIVE_NOVEL_INTELLIGENCE_DISABLED","真实小说智能开关未开启");if(blank(properties.getApiKey()))throw new WorkflowException("NOVEL_INTELLIGENCE_API_KEY_MISSING","真实小说智能缺少 ARK_API_KEY");String model=switch(role){case "novel_analysis"->first(properties.getNovelAnalysisModel(),properties.getTextModel());case "novel_adaptation"->first(properties.getNovelAdaptationModel(),properties.getTextModel());case "novel_screenwriter"->first(properties.getNovelScreenwriterModel(),properties.getTextModel());default->properties.getTextModel();};if(blank(model))throw new WorkflowException("NOVEL_INTELLIGENCE_MODEL_MISSING","小说智能缺少 "+role+" 文本模型");}
    public boolean liveMode(){return "llm".equalsIgnoreCase(mode);}
    private static String first(String value,String fallback){return blank(value)?fallback:value;}
    private static boolean blank(String value){return value==null||value.isBlank();}
}
