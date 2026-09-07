package qupath.ext.quiet.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Name round-trip between the export class pickers and the matchers. */
class ClassNamesTest {

    private static PathObject annotation(PathClass pathClass) {
        ROI roi = ROIs.createRectangleROI(0, 0, 10, 10);
        return pathClass == null
                ? PathObjects.createAnnotationObject(roi)
                : PathObjects.createAnnotationObject(roi, pathClass);
    }

    @Test
    void sentinelIsQuPathsOwnNameForTheNullClass() {
        assertThat(ClassNames.UNCLASSIFIED).isEqualTo("Unclassified");
        assertThat(PathClass.NULL_CLASS.toString()).isEqualTo(ClassNames.UNCLASSIFIED);
    }

    @Test
    void derivedClassMatchesTheNameThePickerShows() {
        PathClass derived = PathClass.fromArray("Tumor", "Stroma");
        assertThat(ClassNames.displayName(derived)).isEqualTo("Tumor: Stroma");
        assertThat(ClassNames.predicate(List.of("Tumor: Stroma")).test(annotation(derived))).isTrue();
    }

    @Test
    void derivedClassDoesNotMatchAnUnrelatedClassOfTheSameBareName() {
        PathObject derived = annotation(PathClass.fromArray("Tumor", "Stroma"));
        assertThat(ClassNames.predicate(List.of("Stroma")).test(derived)).isFalse();
        assertThat(ClassNames.predicate(List.of("Stroma")).test(annotation(PathClass.fromString("Stroma")))).isTrue();
    }

    @Test
    void unclassifiedMatchesOnlyTheUnclassifiedEntry() {
        assertThat(ClassNames.predicate(List.of("Unclassified")).test(annotation(null))).isTrue();
        assertThat(ClassNames.predicate(List.of("Tumor")).test(annotation(null))).isFalse();
        assertThat(ClassNames.predicate(List.of("Unclassified")).test(annotation(PathClass.fromString("Tumor"))))
                .isFalse();
    }

    @Test
    void emptyOrNullFilterMatchesNothing() {
        assertThat(ClassNames.predicate(List.of()).test(annotation(null))).isFalse();
        assertThat(ClassNames.predicate(null).test(annotation(PathClass.fromString("Tumor")))).isFalse();
    }

    @Test
    void displayNameIsSafeForOutputFilenames() {
        // Object-crop folders are named from displayName via
        // GeneralTools.stripInvalidFilenameChars, which drops the ':'.
        String derived = ClassNames.displayName(PathClass.fromArray("Tumor", "Stroma"));
        String other = ClassNames.displayName(PathClass.fromArray("Immune", "Stroma"));
        assertThat(qupath.lib.common.GeneralTools.stripInvalidFilenameChars(derived))
                .isEqualTo("Tumor Stroma");
        assertThat(qupath.lib.common.GeneralTools.stripInvalidFilenameChars(other))
                .isNotEqualTo(qupath.lib.common.GeneralTools.stripInvalidFilenameChars(derived));
    }
}
