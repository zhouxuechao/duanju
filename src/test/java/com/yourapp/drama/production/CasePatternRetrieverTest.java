package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class CasePatternRetrieverTest {
 @Test void returnsAtMostThreeAbstractPatternsWithoutCasePlot(){var values=new CasePatternRetriever().retrieve("SUSPENSE",List.of("HIDDEN_IDENTITY"),"GENERAL","DARK","STANDARD",3);assertThat(values).hasSizeBetween(1,3);assertThat(values).allSatisfy(value->{assertThat(value.structure()).isNotBlank();assertThat(value.plot()).isEmpty();});}
}
