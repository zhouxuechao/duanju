package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.List;

/** Compiles business-owned skill/rule sections before Ark-compatible structured text transport. */
@Component
public class ArkStructuredTextCompiler implements ProviderCompiler {
    private final PromptBudgeter budgeter=new PromptBudgeter();
    @Override public String provider(){return "ARK_STRUCTURED_TEXT";}
    @Override public String compile(ProductionModels.PromptIR ir,List<PromptBudgeter.Section> sections){
        if(ir==null)throw new IllegalArgumentException("PROMPT_IR_REQUIRED");
        return budgeter.compile(sections,96_000);
    }
}
