package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.production.ProviderCapabilityRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.COST_RECORD;
import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class AutomaticRepairGenerationProfileTest {
    @Autowired DocumentStore store;@Autowired JobService jobs;

    @ParameterizedTest @CsvSource({"STANDARD,TEST,480p","FINAL,STANDARD,720p"})
    void frozenAutomaticRepairProfileWinsOverTheCurrentProjectAndCostLedger(String current,String source,String resolution){
        ObjectNode project=obj().put("name","自动返修成本锁定").put("idea","验证自动返修不会升级档位").put("generationProfile",current);
        if("FINAL".equals(current))project.put("imageModel",ProviderCapabilityRegistry.SEEDREAM_50).put("videoModel",ProviderCapabilityRegistry.SEEDANCE_20_FAST).put("imageSize","2K").put("videoResolution","720p");
        ObjectNode savedProject=store.create(PROJECT,project);
        ObjectNode input=obj().put("generationProfile",source).put("modelId",ProviderCapabilityRegistry.SEEDANCE_20_FAST).put("resolution",resolution)
                .put("regenerationMode","REPLAY_ORIGINAL").put("automaticRepair",true);

        ObjectNode queued=jobs.enqueue(id(savedProject),null,"VIDEO",input,"repair-profile-"+current.toLowerCase());
        assertThat(text(queued,"generationProfile")).isEqualTo(source);assertThat(text(queued,"resolution")).isEqualTo(resolution);
        ObjectNode running=jobs.claim(id(savedProject)).orElseThrow();jobs.succeed(id(running),obj());
        assertThat(store.list(COST_RECORD,id(savedProject),null)).singleElement().satisfies(cost->{assertThat(text(cost,"generationProfile")).isEqualTo(source);assertThat(text(cost,"resolution")).isEqualTo(resolution);});
    }
}
