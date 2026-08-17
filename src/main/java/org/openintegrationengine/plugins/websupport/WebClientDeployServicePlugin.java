/*
 * OIE Web Support
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.websupport;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.ExtensionController;

/**
 * Deploys the OIE web-administrator WAR carried by Web Support into the engine.
 *
 * <p>The WAR and its supporting APIs are one extension. Installing Web Support
 * always installs both; uninstalling it removes the deployed WAR.</p>
 *
 * <p>OIE initializes service plugins before its embedded Jetty scans
 * {@code <OIE_HOME>/webapps}, so copying during {@link #init(Properties)} makes the
 * client available on the same restart that completes extension installation.</p>
 *
 * <p><b>Uninstall.</b> The engine's launcher deletes an uninstalled extension's
 * directory before any plugin loads, and there is no uninstall callback, so a plugin
 * cannot remove a file it placed outside its own directory from {@code init()}. The
 * one moment this plugin still runs while its own removal is pending is {@link #stop()}
 * on the shutdown that precedes the uninstall: the engine has already recorded the
 * request in {@code <extensions>/uninstall}. {@link #stop()} therefore removes the
 * deployed WAR when — and only when — this extension is queued for uninstall, so a
 * normal restart still leaves the WAR in place for redeployment.</p>
 */
public class WebClientDeployServicePlugin implements ServicePlugin {

    static final String PLUGIN_POINT = "OIE Web Administrator Deployment";
    /** This extension's install-directory name (its plugin.xml {@code path}). */
    static final String EXTENSION_NAME = "websupport";
    static final String WAR_NAME = "oie-webadmin.war";
    private static final Logger LOGGER = LogManager.getLogger(WebClientDeployServicePlugin.class);

    // Captured at init() so stop() need not reach for controllers during shutdown.
    private volatile Path extensionsPath;
    private volatile Path oieHome;

    @Override
    public void init(Properties properties) {
        ConfigurationController configurationController =
                ControllerFactory.getFactory().createConfigurationController();
        this.extensionsPath = Path.of(ExtensionController.getExtensionsPath());
        this.oieHome = Path.of(configurationController.getBaseDir());
        Path extensionDirectory = extensionsPath.resolve(EXTENSION_NAME);

        try {
            if (installBundledWar(extensionDirectory, oieHome)) {
                LOGGER.info("Installed bundled OIE web administrator at {}",
                        oieHome.resolve("webapps").resolve(WAR_NAME));
            } else {
                // The WAR is part of this extension's package; its absence means a
                // corrupt install rather than a supported configuration.
                LOGGER.warn("No {} found in {}; nothing to deploy", WAR_NAME, extensionDirectory);
            }
        } catch (IOException e) {
            // Never let a failed webapp copy stop the engine from starting.
            LOGGER.error("Could not install the bundled OIE web-administrator WAR", e);
        }
    }

    /**
     * Copies the extension's WAR into OIE's webapps directory, replacing it atomically
     * when the filesystem permits. Returns false when no WAR is present to deploy.
     */
    static boolean installBundledWar(Path extensionDirectory, Path oieHome) throws IOException {
        Path source = extensionDirectory.resolve(WAR_NAME);
        if (!Files.isRegularFile(source)) {
            return false;
        }

        Path webapps = oieHome.resolve("webapps");
        Files.createDirectories(webapps);
        Path destination = webapps.resolve(WAR_NAME);

        if (Files.isRegularFile(destination) && Files.mismatch(source, destination) == -1L) {
            return true;
        }

        Path staged = Files.createTempFile(webapps, "oie-webadmin-", ".war.tmp");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        return true;
    }

    /**
     * True when this extension is listed in the engine's pending-uninstall file, written
     * when an administrator requests removal and consumed by the launcher on next start.
     */
    static boolean isQueuedForUninstall(Path extensionsPath) throws IOException {
        Path uninstallFile = extensionsPath.resolve(ExtensionController.EXTENSIONS_UNINSTALL_FILE);
        if (!Files.isRegularFile(uninstallFile)) {
            return false;
        }
        return Files.readAllLines(uninstallFile).stream()
                .map(String::trim)
                .anyMatch(EXTENSION_NAME::equals);
    }

    /**
     * Removes the deployed WAR only when this extension is queued for uninstall. Returns
     * true when a WAR was deleted. A normal restart leaves the WAR untouched.
     */
    static boolean removeDeployedWarIfUninstalling(Path extensionsPath, Path oieHome) throws IOException {
        if (!isQueuedForUninstall(extensionsPath)) {
            return false;
        }
        return Files.deleteIfExists(oieHome.resolve("webapps").resolve(WAR_NAME));
    }

    @Override
    public void update(Properties properties) {
        // The WAR is re-copied from init() on each start when its bytes change.
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[0];
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Collections.emptyMap();
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT;
    }

    @Override
    public void start() {
        // No background service.
    }

    @Override
    public void stop() {
        Path extensions = this.extensionsPath;
        Path home = this.oieHome;
        if (extensions == null || home == null) {
            return;
        }
        Path war = home.resolve("webapps").resolve(WAR_NAME);
        try {
            if (removeDeployedWarIfUninstalling(extensions, home)) {
                LOGGER.info("Removed {} on uninstall of Web Support", war);
            }
        } catch (IOException e) {
            LOGGER.error("Could not remove {} while uninstalling; delete it manually", war, e);
        }
    }
}
