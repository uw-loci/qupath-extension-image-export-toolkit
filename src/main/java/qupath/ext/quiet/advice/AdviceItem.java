package qupath.ext.quiet.advice;

/**
 * A single advice item from the publication advice checker.
 *
 * @param severity        how important the advice is
 * @param title           short summary shown in the advice panel
 * @param description     full explanation of the issue
 * @param quarepRef       QUAREP-LiMi checklist reference (e.g., "IA-1"), or null
 * @param suggestedAction what the user can do to address the issue
 */
public record AdviceItem(
        AdviceSeverity severity,
        String title,
        String description,
        String quarepRef,
        String suggestedAction
) {
}
