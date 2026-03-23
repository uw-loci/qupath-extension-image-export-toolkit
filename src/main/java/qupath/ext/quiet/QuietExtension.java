package qupath.ext.quiet;

import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.MenuItem;

import qupath.ext.quiet.ui.ExportWizard;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuIET - QuPath Image Export Toolkit.
 * <p>
 * Comprehensive export extension providing rendered overlays, label masks,
 * and raw pixel data export with a wizard UI, script generation, and
 * batch processing.
 *
 * @author Michael Nelson
 */
public class QuietExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(QuietExtension.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.quiet.ui.strings");

    private static final String EXTENSION_NAME = resources.getString("name");
    private static final String EXTENSION_DESCRIPTION = resources.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");
    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-image-export-toolkit");

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return EXTENSION_REPOSITORY;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        logger.info("Installing extension: {}", EXTENSION_NAME);
        Platform.runLater(() -> addMenuItems(qupath));
    }

    private void addMenuItems(QuPathGUI qupath) {
        var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        MenuItem exportItem = new MenuItem(resources.getString("menu.export"));
        // Require a project with at least one image entry
        exportItem.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> qupath.getProject() == null ||
                              qupath.getProject().getImageList().isEmpty(),
                        qupath.projectProperty()
                )
        );
        exportItem.setOnAction(e -> ExportWizard.showWizard(qupath));

        extensionMenu.getItems().add(exportItem);
        logger.info("Menu items added for extension: {}", EXTENSION_NAME);
    }
}
