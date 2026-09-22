package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.List;

/** Converts provider-neutral voice PromptIR sections into Seed Audio text instructions. */
@Component
public class SeedAudioCompiler implements ProviderCompiler {
    private static final List<String> REQUIRED=List.of("[SPEECH CONTENT]","[VOICE IDENTITY]","[OUTPUT CONSTRAINTS]");
    private final PromptBudgeter budgeter=new PromptBudgeter();
    @Override public String provider(){return "SEED_AUDIO";}
    @Override public String compile(ProductionModels.PromptIR ir,List<PromptBudgeter.Section> sections){
        if(ir==null)throw new IllegalArgumentException("PROMPT_IR_REQUIRED");
        String prompt=budgeter.compile(sections,4_000);
        for(String required:REQUIRED)if(!prompt.contains(required))throw new IllegalArgumentException("PROMPT_BUDGET_LOST_REQUIRED_SECTION："+required);
        return prompt;
    }
}
