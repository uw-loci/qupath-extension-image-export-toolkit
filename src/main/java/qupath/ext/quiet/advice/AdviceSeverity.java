package qupath.ext.quiet.advice;

/**
 * Severity levels for publication advice items.
 */
public enum AdviceSeverity {

    /** Potential data integrity issue (e.g., JPEG for label masks). */
    ERROR,

    /** Likely publication quality concern (e.g., missing scale bar). */
    WARNING,

    /** Informational suggestion for best practice. */
    INFO
}
