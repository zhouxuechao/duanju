package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class DeadCodeContractTest {
    @Test void generatedPythonBytecodeCanNeverReenterTheSourceTree() throws Exception{
        try(var files=Files.walk(Path.of("scripts"))){assertThat(files.filter(Files::isRegularFile).map(path->path.getFileName().toString()))
            .noneMatch(name->name.endsWith(".pyc")||name.endsWith(".pyo"));}
        String ignore=Files.readString(Path.of(".gitignore"));
        assertThat(ignore).contains("__pycache__/","*.py[cod]");
    }
}
