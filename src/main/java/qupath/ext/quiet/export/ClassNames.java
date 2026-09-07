package qupath.ext.quiet.export;

import java.util.Collection;
import java.util.function.Predicate;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectPredicates;
import qupath.lib.objects.classes.PathClass;

/**
 * One definition of how a classification is named in the export pickers and
 * how those names are matched back against objects. Matching itself is
 * QuPath's {@link PathObjectPredicates#exactClassification}; only the name
 * round-trip lives here.
 */
public final class ClassNames {

    /**
     * Picker entry standing for objects with no class. This is QuPath's own
     * name for the null class, so it stays in step with the class list, the
     * viewer and the measurement tables.
     */
    public static final String UNCLASSIFIED = PathClass.NULL_CLASS.toString();

    private ClassNames() {}

    /**
     * @param obj object to name (may be null)
     * @return the object's class name as QuPath displays it, including
     *         derived-class colons ("Tumor: Stroma")
     */
    public static String displayName(PathObject obj) {
        return displayName(obj == null ? null : obj.getPathClass());
    }

    /**
     * @param pc class to name (may be null)
     * @return {@code pc.toString()}, or {@link #UNCLASSIFIED} for null / the null class
     */
    public static String displayName(PathClass pc) {
        return pc == null ? UNCLASSIFIED : pc.toString();
    }

    /**
     * @param names class names as shown in a picker; {@link #UNCLASSIFIED}
     *              selects objects with no class
     * @return a predicate accepting objects in one of those classes; matches
     *         nothing when {@code names} is null or empty
     */
    public static Predicate<PathObject> predicate(Collection<String> names) {
        if (names == null || names.isEmpty()) return obj -> false;
        PathClass[] classes = names.stream()
                // fromString("Unclassified") is a real class of that name, not the
                // null class, so the sentinel has to map to null explicitly.
                .map(n -> UNCLASSIFIED.equals(n) ? null : PathClass.fromString(n))
                .toArray(PathClass[]::new);
        return PathObjectPredicates.exactClassification(classes);
    }
}
