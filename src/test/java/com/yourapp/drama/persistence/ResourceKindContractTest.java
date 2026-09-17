package com.yourapp.drama.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceKindContractTest {
    @Test void locationViewsUseLocationAndAssetViewInsteadOfLegacyLocationLook(){
        assertThat(ResourceKind.values()).extracting(Enum::name).doesNotContain("LOCATION_LOOK");
        assertThatThrownBy(()->ResourceKind.fromPath("location-looks"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown resource");
    }
}
