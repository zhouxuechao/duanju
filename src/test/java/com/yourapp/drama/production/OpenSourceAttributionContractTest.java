package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class OpenSourceAttributionContractTest {
    @Test void everyVendoredRuleSourceIsRecordedWithLicenseAndPurpose() throws Exception{
        String text=Files.readString(Path.of("docs/open-source-attribution.md"));
        assertThat(text).contains("source","repository","license","copied files","adapted files","purpose")
            .contains("oiuv/ai-short-drama","lixiaoxiao9888-create/short-drama-factory","lixiaoxiao9888-create/manju-laoli-skill")
            .contains("MIT","4f318097c54a2e24ea34d3c9d23d30f5ec332f11","edd0df754320c2f3949fb198cea7847c71d0cde0","079df685f7cf2f0de635362bd359c233db38f9fe");
        assertThat(Path.of("skills/vendor/short-drama-factory/LICENSE")).exists();
        assertThat(Path.of("skills/vendor/manju-laoli-skill/LICENSE")).exists();
        assertThat(Path.of("skills/vendor/oiuv-ai-short-drama/UPSTREAM.md")).exists();
    }
}
