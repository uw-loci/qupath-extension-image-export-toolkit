package qupath.ext.quiet.advice;

/**
 * A single advice item from the publication advice checker.
 *
 * @param severity        how important the advice is
 * @param title           short summary shown in the advice panel
 * @param description     full explanation of the issue
 * @param quarepRef       QUAREP-LiMi checklist reference (e.g., "IA-1"), or null
 * @param suggestedAction what the user can do to address the issue
 * @param configSection   identifier for the config pane section this advice relates to
 *                        (e.g., "scaleBar", "format", "displaySettings", "splitChannel"),
 *                        or null if the advice is not directly tied to a config control
 */
public record AdviceItem(
        AdviceSeverity severity,
        String title,
        String description,
        String quarepRef,
        String suggestedAction,
        String configSection
) {
}
