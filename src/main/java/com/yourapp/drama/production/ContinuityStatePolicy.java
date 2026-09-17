package com.yourapp.drama.production;

import com.yourapp.drama.domain.ShotRelation;
import java.util.Set;

/** The same state inheritance rules apply at planning and media production. */
final class ContinuityStatePolicy {
    private ContinuityStatePolicy() {}

    static boolean inherits(String path, ShotRelation relation) {
        if (relation == ShotRelation.CONTINUOUS) return true;
        String field = path.substring(path.lastIndexOf('.') + 1);
        if (path.startsWith("characters."))
            return !Set.of("pose", "actionState", "lookDirection").contains(field);
        if (path.startsWith("props."))
            return !Set.of("position", "state").contains(field);
        return true;
    }
}
