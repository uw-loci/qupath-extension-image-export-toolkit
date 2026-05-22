package qupath.ext.quiet.export;

import java.util.Collections;
import java.util.List;

/**
 * Result of a batch export operation.
 */
public class ExportResult {

    private final int succeeded;
    private final int failed;
    private final int skipped;
    private final List<String> errors;
    private final String customSummary;

    public ExportResult(int succeeded, int failed, int skipped, List<String> errors) {
        this(succeeded, failed, skipped, errors, null);
    }

    /**
     * Create a result with a caller-supplied summary line. Used by export
     * modes (e.g. Panel / Montage) whose output is not "N images" so the
     * default summary text does not apply.
     *
     * @param succeeded     count of successful units
     * @param failed        count of failed units
     * @param skipped       count of skipped units
     * @param errors        per-unit error messages
     * @param customSummary an explicit summary line, or null for the default
     */
    public ExportResult(int succeeded, int failed, int skipped,
                        List<String> errors, String customSummary) {
        this.succeeded = succeeded;
        this.failed = failed;
        this.skipped = skipped;
        this.errors = errors == null ? Collections.emptyList() : List.copyOf(errors);
        this.customSummary = customSummary;
    }

    public int getSucceeded() {
        return succeeded;
    }

    public int getFailed() {
        return failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public List<String> getErrors() {
        return errors;
    }

    public boolean hasErrors() {
        return failed > 0;
    }

    /**
     * Build a human-readable summary of the export result.
     *
     * @return summary string
     */
    public String getSummary() {
        if (customSummary != null) {
            return customSummary;
        }
        var sb = new StringBuilder();
        sb.append(String.format("Exported %d images.", succeeded));
        if (skipped > 0) {
            sb.append(String.format(" %d skipped (incompatible).", skipped));
        }
        if (failed > 0) {
            sb.append(String.format(" %d failed.", failed));
        }
        return sb.toString();
    }
}
