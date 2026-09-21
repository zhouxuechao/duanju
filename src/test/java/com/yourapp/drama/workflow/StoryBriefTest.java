package com.yourapp.drama.workflow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class StoryBriefTest {
 @Test void schemaPersistsIntentConflictCostInformationGapAndHardConstraints(){var required=StoryDevelopmentSchemas.storyBrief().path("required");assertThat(required).extracting(n->n.asText()).contains("originalIdea","hardConstraints","protagonist","opponent","goal","coreConflict","failureCost","informationGap","endingDirection","mustKeep","mustNotChange");}
}
