package com.yourapp.drama.production;

import java.util.List;

public final class DeterministicRuleViolationException extends IllegalArgumentException {
    private final List<ProductionModels.Risk> risks;
    public DeterministicRuleViolationException(List<ProductionModels.Risk> risks){
        super(risks.getFirst().code()+"："+risks.getFirst().message());
        this.risks=List.copyOf(risks);
    }
    public List<ProductionModels.Risk> risks(){return risks;}
}
