package qupath.ext.quiet.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * Tests for the image filter predicate logic used in ImageSelectionPane.
 * <p>
 * The filter predicate is: item name (case-insensitive) contains filter text.
 * These tests verify the predicate independently of the UI.
 */
class ImageFilterTest {

    /**
     * Builds the same predicate used by ImageSelectionPane.
     */
    private static Predicate<String> buildFilterPredicate(String filterText) {
        if (filterText == null || filterText.isEmpty()) {
            return name -> true;
        }
        String lower = filterText.toLowerCase(Locale.ROOT);
        return name -> name.toLowerCase(Locale.ROOT).contains(lower);
    }

    @Test
    void testEmptyFilterShowsAll() {
        var predicate = buildFilterPredicate("");
        assertTrue(predicate.test("slide_001"));
        assertTrue(predicate.test("anything"));
        assertTrue(predicate.test(""));
    }

    @Test
    void testNullFilterShowsAll() {
        var predicate = buildFilterPredicate(null);
        assertTrue(predicate.test("slide_001"));
        assertTrue(predicate.test("anything"));
    }

    @Test
    void testCaseInsensitiveMatch() {
        var predicate = buildFilterPredicate("SLIDE");
        assertTrue(predicate.test("slide_001"));
        assertTrue(predicate.test("Slide_002"));
        assertTrue(predicate.test("SLIDE_003"));
    }

    @Test
    void testPartialMatch() {
        var predicate = buildFilterPredicate("001");
        assertTrue(predicate.test("slide_001"));
        assertFalse(predicate.test("slide_002"));
    }

    @Test
    void testNoMatch() {
        var predicate = buildFilterPredicate("xyz");
        assertFalse(predicate.test("slide_001"));
        assertFalse(predicate.test("sample_abc"));
    }

    @Test
    void testMatchAtStart() {
        var predicate = buildFilterPredicate("slide");
        assertTrue(predicate.test("slide_001"));
        assertFalse(predicate.test("my_sample"));
    }

    @Test
    void testMatchAtEnd() {
        var predicate = buildFilterPredicate("tif");
        assertTrue(predicate.test("image.tif"));
        assertFalse(predicate.test("image.png"));
    }

    @Test
    void testMatchMiddle() {
        var predicate = buildFilterPredicate("sample");
        assertTrue(predicate.test("my_sample_01"));
        assertFalse(predicate.test("my_image_01"));
    }
}
