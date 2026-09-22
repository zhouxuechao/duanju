package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SeedanceCompiler implements ProviderCompiler {
    private static final List<String> REQUIRED=List.of("[REFERENCE AUTHORITY]","[CURRENT ACTION / ENDPOINT]","[HIGH-RISK CONTINUITY LOCKS]");
    private final PromptBudgeter budgeter=new PromptBudgeter();
    @Override public String provider(){return "SEEDANCE";}
    @Override public String compile(ProductionModels.PromptIR ir,List<PromptBudgeter.Section> sections){
        if(ir==null)throw new IllegalArgumentException("PROMPT_IR_REQUIRED");
        String prompt=budgeter.compile(sections,10_000);
        for(String required:REQUIRED)if(!prompt.contains(required))throw new IllegalArgumentException("PROMPT_BUDGET_LOST_REQUIRED_SECTION："+required);
        return prompt;
    }
}
