package com.yourapp.drama.production;

/** Resolves image quality and aspect-ratio intent without inventing undocumented pixel dimensions. */
public final class ImageOutputSizeResolver {
    private final ProviderCapabilityRegistry capabilities;
    public ImageOutputSizeResolver(ProviderCapabilityRegistry capabilities){this.capabilities=capabilities;}
    public ImageOutputProfile resolve(String modelId,String imageQuality,String aspectRatio){
        ImageModelProfile profile=capabilities.imageProfile(modelId);profile.requireSize(imageQuality);
        return profile.outputProfiles().stream().filter(output->imageQuality.equals(output.imageQuality())&&aspectRatio.equals(output.aspectRatioIntent())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("IMAGE_OUTPUT_PROFILE_UNVERIFIED: "+modelId+" "+imageQuality+" "+aspectRatio));
    }
}
