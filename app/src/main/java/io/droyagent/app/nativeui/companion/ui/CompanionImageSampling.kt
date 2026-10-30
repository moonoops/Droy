package io.droyagent.app.nativeui.companion.ui

/** Cropped avatars need sufficient pixels on the short edge, not just the long edge. */
internal fun companionImageSampleSize(width: Int, height: Int, maximumDimension: Int, cropTargetPixels: Int? = null): Int {
    require(width > 0 && height > 0 && maximumDimension > 0)
    var sample = 1
    if (cropTargetPixels != null) {
        require(cropTargetPixels > 0)
        while (minOf(width, height) / (sample * 2) >= cropTargetPixels) sample *= 2
    } else {
        while (maxOf(width, height) / sample > maximumDimension) sample *= 2
    }
    return sample
}
