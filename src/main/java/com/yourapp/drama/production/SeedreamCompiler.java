package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SeedreamCompiler implements ProviderCompiler {
    private final PromptBudgeter budgeter=new PromptBudgeter();
    @Override public String provider(){return "SEEDREAM";}
    @Override public String compile(ProductionModels.PromptIR ir,List<PromptBudgeter.Section> sections){
        if(ir==null)throw new IllegalArgumentException("PROMPT_IR_REQUIRED");
        return budgeter.compile(sections,12_000);
    }
}
