package com.yourapp.drama.production;

import java.util.List;

/** Translates provider-neutral PromptIR and prepared sections into one provider protocol. */
public interface ProviderCompiler {
    String provider();
    String compile(ProductionModels.PromptIR ir,List<PromptBudgeter.Section> sections);
}
