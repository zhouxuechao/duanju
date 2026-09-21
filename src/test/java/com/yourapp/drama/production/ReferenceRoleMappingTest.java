package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReferenceRoleMappingTest {
    @Test void canonicalRolesAreStableAndLegacyAliasesOnlyParseAtBoundary() {
        assertThat(ReferenceBinding.Role.PREVIOUS_TAKE.name()).isEqualTo("PREVIOUS_TAKE");
        assertThat(ReferenceBinding.Role.from("PREVIOUS_TAKE_REFERENCE")).isEqualTo(ReferenceBinding.Role.PREVIOUS_TAKE);
        assertThat(ReferenceBinding.Role.from("PREVIOUS_LOCKED_TAKE")).isEqualTo(ReferenceBinding.Role.PREVIOUS_TAKE);
        assertThat(ReferenceBinding.Role.from("PREVIOUS_VIDEO")).isEqualTo(ReferenceBinding.Role.PREVIOUS_TAKE);
        assertThat(ReferenceBinding.Role.from("PREVIOUS_LAST_FRAME")).isEqualTo(ReferenceBinding.Role.PREVIOUS_LAST_FRAME);
    }

    @Test void unknownRoleCannotSilentlyReachProvider() {
        assertThatThrownBy(() -> ReferenceBinding.Role.from("WHATEVER_VIDEO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REFERENCE_ROLE_UNSUPPORTED");
    }
}
