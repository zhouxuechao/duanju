package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class UsageCostLedgerIntegrationTest {
    @Autowired DocumentStore store;@Autowired JobService jobs;
    @Test void terminalJobsAlwaysCreateOneAttributableCostRecord(){ObjectNode project=store.create(PROJECT,obj().put("name","成本追踪").put("idea","测试"));ObjectNode success=jobs.enqueue(id(project),null,"STORY",obj().put("episodeNo",1),"cost-success");success=jobs.claim().orElseThrow();jobs.mutate(id(success),job->{job.put("provider","VOLCENGINE").put("model","writer").put("cost",.12).put("costKnown",false);job.set("providerUsage",obj().put("promptTokens",100).put("completionTokens",50).put("totalTokens",150));});String successId=id(success);jobs.succeed(successId,obj());ObjectNode failed=jobs.enqueue(id(project),null,"KEYFRAME",obj(),"cost-failed");failed=jobs.claim().orElseThrow();jobs.mutate(id(failed),job->job.put("providerRequestId","request-billable").put("cost",.08));String failedId=id(failed);jobs.fail(failedId,"NETWORK_ERROR","response lost",false,true);List<ObjectNode> records=store.list(COST_RECORD,id(project),null);assertThat(records).hasSize(2);ObjectNode successCost=records.stream().filter(r->successId.equals(text(r,"generationJobId"))).findFirst().orElseThrow();assertThat(text(successCost,"taskType")).isEqualTo("STORY");assertThat(successCost.path("inputTokens").asInt()).isEqualTo(100);assertThat(text(successCost,"billingStatus")).isEqualTo("ESTIMATED");ObjectNode failedCost=records.stream().filter(r->failedId.equals(text(r,"generationJobId"))).findFirst().orElseThrow();assertThat(failedCost.path("wastedCost").asDouble()).isEqualTo(.08);assertThat(text(failedCost,"billingStatus")).isEqualTo("POSSIBLY_BILLED");}
}
