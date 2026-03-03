package qupath.ext.quiet.advice;

import java.util.List;

/**
 * Captures per-image metadata needed for publication advice checks.
 * <p>
 * Built from image server metadata without reading pixel data.
 *
 * @param hasPixelCalibration whether the image has pixel size calibration
 * @param imageType           the QuPath image type name (e.g., "BRIGHTFIELD_H_E", "FLUORESCENCE")
 * @param channelNames        names of all channels
 * @param channelColors       packed ARGB color ints for each channel
 * @param nChannels           number of channels
 */
public record ImageContext(
        boolean hasPixelCalibration,
        String imageType,
        List<String> channelNames,
        List<Integer> channelColors,
        int nChannels
) {

    /**
     * Returns true if the image is a brightfield type (H&E, H-DAB, or other brightfield).
     */
    public boolean isBrightfield() {
        return imageType != null && imageType.startsWith("BRIGHTFIELD");
    }

    /**
     * Returns true if the image is fluorescence type.
     */
    public boolean isFluorescence() {
        return "FLUORESCENCE".equals(imageType);
    }
}
