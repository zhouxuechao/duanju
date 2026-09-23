package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.yourapp.drama.workflow.Documents.obj;

/** Auditable mapping from product image intent to the exact provider size option. */
public record ImageOutputProfile(
        String imageQuality,String aspectRatioIntent,String providerSize,
        boolean providerAspectRatioVerified,String verificationStatus) {
    public ObjectNode toJson(){return obj().put("imageQuality",imageQuality).put("aspectRatioIntent",aspectRatioIntent)
            .put("providerSize",providerSize).put("providerAspectRatioVerified",providerAspectRatioVerified)
            .put("verificationStatus",verificationStatus);}
}
