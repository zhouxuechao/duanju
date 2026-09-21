package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class PipelinePreflightIntegrationTest {
    @Autowired DocumentStore store;@Autowired PipelinePreflightService preflight;
    @Test void mockPreflightValidatesTheActualRuntimeProductionDependencies(){ObjectNode project=store.create(PROJECT,obj().put("name","全链路自检").put("idea","两个村民在祠堂寻找一只失踪的铜铃").put("targetDuration",24));ObjectNode result=preflight.review(project.path("id").asText(),"MOCK");assertThat(result.path("ready").asBoolean()).isTrue();assertThat(result.path("blocking")).isEmpty();assertThat(result.path("checks").toString()).contains("DATABASE_OK","STORY_SKILLS_OK","VENDOR_RULES_OK","RUNTIME_RULE_PACK_OK","SKILL_MANIFEST_OK","VIDEO_MODEL_PROFILE_OK","BUDGET_GUARD_OK","PROVIDER_MOCK_OK");}
}
