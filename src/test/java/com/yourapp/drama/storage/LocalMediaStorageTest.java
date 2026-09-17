package com.yourapp.drama.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.*;

class LocalMediaStorageTest {
    @TempDir Path root;
    @Test void storesInRootAndRejectsTraversal()throws Exception{
        LocalMediaStorage storage=new LocalMediaStorage(root.toString());
        assertThat(storage.put("takes/take1.mp4",new ByteArrayInputStream(new byte[]{1,2,3}),"video/mp4")).isEqualTo("/api/media/takes/take1.mp4");
        try(var stream=storage.open("takes/take1.mp4")){assertThat(stream.readAllBytes()).containsExactly(1,2,3);}
        assertThatThrownBy(()->storage.open("../secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->storage.open("C:/secret")).isInstanceOf(IllegalArgumentException.class);
    }
}
