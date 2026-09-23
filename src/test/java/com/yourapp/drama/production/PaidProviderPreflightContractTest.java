package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PaidProviderPreflightContractTest {
    @Test void workerRunsCentralPreflightBeforeDispatchingAnyPaidMediaJob() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/yourapp/drama/job/GenerationWorker.java"));
        int preflight=source.indexOf("paidPreflight.requirePass(job)");
        int dispatch=source.indexOf("switch(text(job,\"type\"))");
        assertThat(preflight).isGreaterThan(0);
        assertThat(preflight).isLessThan(dispatch);
    }
}
