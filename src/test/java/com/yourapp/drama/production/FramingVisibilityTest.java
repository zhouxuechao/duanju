package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class FramingVisibilityTest {
 @Test void closeUpCannotPromiseADistantCharactersVisibleMicroExpression(){var v=new FramingVisibilityValidator();assertThat(v.validate("CLOSE_UP","NEAR_CH01","FAR_CH02","MICRO_EXPRESSION").status()).isEqualTo(FramingVisibilityValidator.Status.SHOT_SCALE_CONFLICT);assertThat(v.validate("WIDE","ROOM_ENTRANCE","ROOM_CENTER","ACTION").status()).isEqualTo(FramingVisibilityValidator.Status.VISIBLE);}
}
