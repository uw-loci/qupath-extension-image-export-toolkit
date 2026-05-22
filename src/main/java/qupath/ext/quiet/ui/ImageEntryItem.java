package qupath.ext.quiet.ui;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Map;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import qupath.lib.projects.ProjectImageEntry;

/**
 * Wrapper around a {@link ProjectImageEntry} for use in a CheckBoxListCell.
 * <p>
 * The wrapper optionally caches the image type and a metadata map captured at
 * list-build time, so the panel-mode image filter facets (type, metadata) can
 * evaluate their predicate without re-opening images on every keystroke.
 */
public class ImageEntryItem {

    private final ProjectImageEntry<BufferedImage> entry;
    private final BooleanProperty selected;

    /** Cached image type display name, or null if unknown / not scanned. */
    private String imageType;

    /** Cached metadata snapshot (entry user metadata), never null. */
    private Map<String, String> metadata = Collections.emptyMap();

    public ImageEntryItem(ProjectImageEntry<BufferedImage> entry, boolean selected) {
        this.entry = entry;
        this.selected = new SimpleBooleanProperty(selected);
    }

    public ProjectImageEntry<BufferedImage> getEntry() {
        return entry;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    /**
     * The cached image type display name, or null if it was not scanned.
     */
    public String getImageType() {
        return imageType;
    }

    public void setImageType(String imageType) {
        this.imageType = imageType;
    }

    /**
     * The cached metadata snapshot. Never null.
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
    }

    @Override
    public String toString() {
        return entry.getImageName();
    }
}
