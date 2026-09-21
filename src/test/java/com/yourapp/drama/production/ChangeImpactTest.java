package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
class ChangeImpactTest {
 @Test void scopesReviewBySemanticImpact(){var a=new ChangeImpactAnalyzer();assertThat(a.analyze("SHOT",Set.of("cameraPlan.lensMm"),1).scope()).isEqualTo(ChangeImpactAnalyzer.Scope.LOCAL);assertThat(a.analyze("LOOK",Set.of("wardrobe"),8).scope()).isEqualTo(ChangeImpactAnalyzer.Scope.DEPENDENT_RESOURCE);assertThat(a.analyze("CHARACTER",Set.of("identity.face"),40).scope()).isEqualTo(ChangeImpactAnalyzer.Scope.FULL_REVIEW);}
}
