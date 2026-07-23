package qupath.ext.quiet;

import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.Bindings;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;

import qupath.ext.quiet.preferences.QuietPreferences;
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

    /** The QuIET extension version, for provenance records. */
    public static final String QUIET_VERSION = "1.0.0";

    private static final String EXTENSION_NAME = resources.getString("name");
    private static final String EXTENSION_DESCRIPTION = resources.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");
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

        // Require a project with at least one image entry. Both menu items
        // share this gate.
        BooleanBinding noUsableProject = Bindings.createBooleanBinding(
                () -> qupath.getProject() == null ||
                      qupath.getProject().getImageList().isEmpty(),
                qupath.projectProperty()
        );

        // -- Image Export... (normal wizard, with the category picker) --
        MenuItem exportItem = new MenuItem(resources.getString("menu.export"));
        exportItem.disableProperty().bind(noUsableProject);
        exportItem.setOnAction(e -> ExportWizard.showWizard(qupath));

        // -- Panel / Montage Export... (panel wizard, skips the category
        // picker). A plain MenuItem: the one-time intro dialog explains panel
        // mode on first use, so no hover tooltip (and no popup-fragile
        // CustomMenuItem) is needed on the menu.
        MenuItem panelItem = new MenuItem(resources.getString("menu.panel"));
        panelItem.disableProperty().bind(noUsableProject);
        panelItem.setOnAction(e -> {
            maybeShowPanelIntro(qupath);
            ExportWizard.showPanelWizard(qupath);
        });

        extensionMenu.getItems().addAll(exportItem, panelItem);
        logger.info("Menu items added for extension: {}", EXTENSION_NAME);
    }

    /**
     * Show the one-time panel-mode intro dialog if the user has not yet opted
     * out. The dialog is informational only -- the wizard opens regardless of
     * the user's choice. If the user checks "do not show again", the
     * {@code quiet.panel.showIntroDialog} preference is set to false.
     *
     * @param qupath the QuPath GUI instance, used as the dialog owner
     */
    private static void maybeShowPanelIntro(QuPathGUI qupath) {
        if (!QuietPreferences.isPanelShowIntroDialog()) {
            return;
        }
        var alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.initOwner(qupath.getStage());
        alert.initModality(javafx.stage.Modality.WINDOW_MODAL);
        alert.setTitle(resources.getString("panel.intro.title"));
        alert.setHeaderText(resources.getString("panel.intro.header"));

        var contentLabel = new Label(resources.getString("panel.intro.content"));
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(440);

        var doNotShowAgain = new CheckBox(
                resources.getString("panel.intro.doNotShowAgain"));

        var box = new javafx.scene.layout.VBox(12, contentLabel, doNotShowAgain);
        box.setPadding(new javafx.geometry.Insets(4));
        alert.getDialogPane().setContent(box);

        alert.showAndWait();
        if (doNotShowAgain.isSelected()) {
            QuietPreferences.setPanelShowIntroDialog(false);
        }
    }
}
