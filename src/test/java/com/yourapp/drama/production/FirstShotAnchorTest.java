package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class FirstShotAnchorTest {
 @Test void rejectsAmbiguousOwnerAndDanglingBodyPart(){var v=new FirstShotAnchorValidator();assertThat(v.validate("他和她站在门边，一只手握住门把")).extracting(ProductionModels.Risk::code).contains("MULTI_SUBJECT_AMBIGUITY","DANGLING_BODY_PART_REFERENCE");assertThat(v.validate("林夏站在门内，林夏的右手握住铜制门把")).isEmpty();}
}
