package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.PRICE_SNAPSHOT;
import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.workflow.Documents.id;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class CostEstimatorTest {
    @Autowired DocumentStore store;
    @Autowired CostEstimator estimator;

    @Test void estimatesEveryPaidMediaFamilyAndExpectedRetriesFromVersionedPrices(){
        ObjectNode project=store.create(PROJECT,obj().put("name","成本估算").put("idea","透明估算"));
        ObjectNode prices=obj().put("imageRequest",.5).put("videoSecond",.2)
            .put("llmInputMillionTokens",10).put("llmOutputMillionTokens",20)
            .put("vlmReview",.3).put("ttsThousandCharacters",.4);
        ObjectNode multipliers=obj().put("PREVIEW",.5).put("STANDARD",1).put("FINAL",1.5);
        ObjectNode snapshot=obj().put("projectId",id(project)).put("version",3)
            .put("currency","CNY").put("status","PRICED").put("source","LOCAL_CONFIG");
        snapshot.set("prices",prices);snapshot.set("strategyMultipliers",multipliers);
        store.create(PRICE_SNAPSHOT,snapshot);

        ObjectNode workload=obj().put("imageRequests",2).put("videoSeconds",10)
            .put("llmInputTokens",100_000).put("llmOutputTokens",50_000)
            .put("vlmReviews",2).put("ttsCharacters",1_000);
        ObjectNode request=obj().put("strategy","FINAL").put("expectedRetryRate",.2);
        request.set("workload",workload);
        ObjectNode result=estimator.estimate(id(project),request);

        assertThat(result.path("pricingStatus").asText()).isEqualTo("PRICED");
        assertThat(result.path("priceSnapshotVersion").asInt()).isEqualTo(3);
        assertThat(result.path("strategy").asText()).isEqualTo("FINAL");
        assertThat(result.path("subtotal").asDouble()).isEqualTo(9);
        assertThat(result.path("expectedRetryCost").asDouble()).isEqualTo(1.8);
        assertThat(result.path("estimatedTotal").asDouble()).isEqualTo(10.8);
        assertThat(result.path("lineItems")).hasSize(6);
        assertThat(result.path("workload")).isEqualTo(workload);
    }

    @Test void missingPriceSnapshotIsExplicitlyUnpricedInsteadOfReturningFalseZero(){
        ObjectNode project=store.create(PROJECT,obj().put("name","未定价").put("idea","不伪造金额"));
        ObjectNode request=obj().put("strategy","STANDARD");request.set("workload",obj().put("videoSeconds",24));
        ObjectNode result=estimator.estimate(id(project),request);
        assertThat(result.path("pricingStatus").asText()).isEqualTo("UNPRICED");
        assertThat(result.path("estimateKnown").asBoolean()).isFalse();
        assertThat(result.has("estimatedTotal")).isFalse();
        assertThat(result.path("missingPriceKeys")).containsExactly(obj().textNode("videoSecond"));
    }
}
