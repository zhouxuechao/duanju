package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class GenerationProfileIntegrationTest {
    @Autowired StudioService studio;
    @Autowired DocumentStore store;
    @Autowired JobService jobs;

    @Test void newProjectsJobsAndCostRecordsKeepTheSelectedProfile(){
        ObjectNode project=studio.create(PROJECT,obj().put("name","低成本验证").put("idea","完整链路"));
        assertThat(text(project,"generationProfile")).isEqualTo("TEST");
        ObjectNode job=jobs.enqueue(id(project),null,"STORY",obj(),"profile-ledger");
        assertThat(text(job,"generationProfile")).isEqualTo("TEST");
        job=jobs.claim(id(project)).orElseThrow();
        jobs.succeed(id(job),obj());
        assertThat(store.list(COST_RECORD,id(project),null)).singleElement().satisfies(cost->{
            assertThat(text(cost,"generationProfile")).isEqualTo("TEST");
            assertThat(text(cost,"model")).isNotBlank();
        });
    }
}
