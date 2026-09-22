package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.yourapp.drama.persistence.ResourceKind.PRICE_SNAPSHOT;
import static com.yourapp.drama.workflow.Documents.id;
import static com.yourapp.drama.workflow.Documents.obj;
import static com.yourapp.drama.workflow.Documents.text;

/** Advisory estimate based on an immutable, versioned price snapshot. */
@Service
public class CostEstimator {
    private static final List<CostUnit> UNITS=List.of(
        new CostUnit("IMAGE","imageRequests","imageRequest",1),
        new CostUnit("VIDEO","videoSeconds","videoSecond",1),
        new CostUnit("LLM_INPUT","llmInputTokens","llmInputMillionTokens",1_000_000),
        new CostUnit("LLM_OUTPUT","llmOutputTokens","llmOutputMillionTokens",1_000_000),
        new CostUnit("VLM","vlmReviews","vlmReview",1),
        new CostUnit("TTS","ttsCharacters","ttsThousandCharacters",1_000)
    );
    private final DocumentStore store;

    public CostEstimator(DocumentStore store){this.store=store;}

    public ObjectNode estimate(String projectId,JsonNode request){
        String strategy=request.path("strategy").asText("STANDARD").toUpperCase(Locale.ROOT);
        if(!List.of("PREVIEW","STANDARD","FINAL").contains(strategy))throw new WorkflowException("GENERATION_STRATEGY_INVALID","生成策略必须是 PREVIEW、STANDARD 或 FINAL");
        double retryRate=request.path("expectedRetryRate").asDouble(0);
        if(!Double.isFinite(retryRate)||retryRate<0||retryRate>1)throw new WorkflowException("RETRY_RATE_INVALID","预计重试率必须在 0 到 1 之间");
        JsonNode workload=request.path("workload");
        if(!workload.isObject())throw new WorkflowException("COST_WORKLOAD_REQUIRED","成本估算需要 workload");
        for(CostUnit unit:UNITS){double quantity=workload.path(unit.quantityKey()).asDouble(0);if(!Double.isFinite(quantity)||quantity<0)throw new WorkflowException("COST_WORKLOAD_INVALID",unit.quantityKey()+" 不能为负数");}

        Optional<ObjectNode> snapshot=store.list(PRICE_SNAPSHOT,projectId,null).stream().max(Comparator.comparingInt(value->value.path("version").asInt()));
        ObjectNode result=obj().put("projectId",projectId).put("strategy",strategy).put("expectedRetryRate",retryRate).put("advisory",true);
        result.set("workload",workload.deepCopy());
        if(snapshot.isEmpty())return unpriced(result,null,workload);
        ObjectNode selected=snapshot.orElseThrow();JsonNode prices=selected.path("prices");
        result.put("priceSnapshotId",id(selected)).put("priceSnapshotVersion",selected.path("version").asInt()).put("currency",selected.path("currency").asText("CNY"));
        ArrayNode missing=result.putArray("missingPriceKeys");
        for(CostUnit unit:UNITS)if(workload.path(unit.quantityKey()).asDouble(0)>0&&!prices.path(unit.priceKey()).isNumber())missing.add(unit.priceKey());
        if(!"PRICED".equals(text(selected,"status"))||!missing.isEmpty())return unpriced(result,selected,workload);

        double multiplier=selected.path("strategyMultipliers").path(strategy).asDouble(1);
        if(!Double.isFinite(multiplier)||multiplier<=0)throw new WorkflowException("STRATEGY_PRICE_INVALID","价格快照中的生成策略倍率必须大于 0");
        ArrayNode lines=result.putArray("lineItems");BigDecimal base=BigDecimal.ZERO;
        for(CostUnit unit:UNITS){
            double rawQuantity=workload.path(unit.quantityKey()).asDouble(0);if(rawQuantity==0)continue;
            BigDecimal quantity=BigDecimal.valueOf(rawQuantity).divide(BigDecimal.valueOf(unit.divisor()),12,RoundingMode.HALF_UP);
            BigDecimal rate=BigDecimal.valueOf(prices.path(unit.priceKey()).asDouble());
            BigDecimal amount=quantity.multiply(rate);base=base.add(amount);
            lines.add(obj().put("category",unit.category()).put("quantity",rawQuantity).put("quantityKey",unit.quantityKey()).put("priceKey",unit.priceKey()).put("unitRate",rate.doubleValue()).put("baseCost",money(amount)));
        }
        BigDecimal subtotal=base.multiply(BigDecimal.valueOf(multiplier));BigDecimal retries=subtotal.multiply(BigDecimal.valueOf(retryRate));
        return result.put("pricingStatus","PRICED").put("estimateKnown",true).put("strategyMultiplier",multiplier)
            .put("baseCost",money(base)).put("subtotal",money(subtotal)).put("expectedRetryCost",money(retries)).put("estimatedTotal",money(subtotal.add(retries)));
    }

    private ObjectNode unpriced(ObjectNode result,ObjectNode snapshot,JsonNode workload){
        result.put("pricingStatus","UNPRICED").put("estimateKnown",false);
        ArrayNode missing=result.withArray("missingPriceKeys");
        if(missing.isEmpty())for(CostUnit unit:UNITS)if(workload.path(unit.quantityKey()).asDouble(0)>0)missing.add(unit.priceKey());
        if(snapshot!=null&&!result.has("priceSnapshotId"))result.put("priceSnapshotId",id(snapshot)).put("priceSnapshotVersion",snapshot.path("version").asInt()).put("currency",snapshot.path("currency").asText("CNY"));
        return result;
    }
    private double money(BigDecimal value){return value.setScale(6,RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();}
    private record CostUnit(String category,String quantityKey,String priceKey,long divisor){}
}
