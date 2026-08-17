/*
 * OIE Web Support
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.websupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mirth.connect.server.controllers.ExtensionController;

class WebClientDeployServicePluginTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void noWarPresentDoesNotCreateAWebapp() throws Exception {
        Path extension = temporaryDirectory.resolve("extensions/" + WebClientDeployServicePlugin.EXTENSION_NAME);
        Path oieHome = temporaryDirectory.resolve("oie");
        Files.createDirectories(extension);

        assertFalse(WebClientDeployServicePlugin.installBundledWar(extension, oieHome));
        assertFalse(Files.exists(oieHome.resolve("webapps/oie-webadmin.war")));
    }

    @Test
    void installsAndUpdatesTheWar() throws Exception {
        Path extension = temporaryDirectory.resolve("extensions/" + WebClientDeployServicePlugin.EXTENSION_NAME);
        Path oieHome = temporaryDirectory.resolve("oie");
        Path source = extension.resolve(WebClientDeployServicePlugin.WAR_NAME);
        Path destination = oieHome.resolve("webapps").resolve(WebClientDeployServicePlugin.WAR_NAME);
        Files.createDirectories(extension);
        Files.createDirectories(destination.getParent());

        Files.write(source, "first-war".getBytes(StandardCharsets.UTF_8));
        Files.write(destination, "old-war".getBytes(StandardCharsets.UTF_8));
        assertTrue(WebClientDeployServicePlugin.installBundledWar(extension, oieHome));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(destination));

        Files.write(source, "updated-war".getBytes(StandardCharsets.UTF_8));
        assertTrue(WebClientDeployServicePlugin.installBundledWar(extension, oieHome));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(destination));

        try (var entries = Files.list(destination.getParent())) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".war.tmp")));
        }
    }

    @Test
    void removesTheWarWhenQueuedForUninstall() throws Exception {
        Path extensions = temporaryDirectory.resolve("extensions");
        Path oieHome = temporaryDirectory.resolve("oie");
        Path war = oieHome.resolve("webapps").resolve(WebClientDeployServicePlugin.WAR_NAME);
        Files.createDirectories(extensions);
        Files.createDirectories(war.getParent());
        Files.write(war, "war".getBytes(StandardCharsets.UTF_8));
        // The engine records pending uninstalls one extension path per line.
        Files.write(extensions.resolve(ExtensionController.EXTENSIONS_UNINSTALL_FILE),
                ("some-other-extension\n" + WebClientDeployServicePlugin.EXTENSION_NAME + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        assertTrue(WebClientDeployServicePlugin.removeDeployedWarIfUninstalling(extensions, oieHome));
        assertFalse(Files.exists(war));
    }

    @Test
    void keepsTheWarOnANormalRestart() throws Exception {
        Path extensions = temporaryDirectory.resolve("extensions");
        Path oieHome = temporaryDirectory.resolve("oie");
        Path war = oieHome.resolve("webapps").resolve(WebClientDeployServicePlugin.WAR_NAME);
        Files.createDirectories(extensions);
        Files.createDirectories(war.getParent());
        Files.write(war, "war".getBytes(StandardCharsets.UTF_8));

        // No uninstall file at all — an ordinary restart.
        assertFalse(WebClientDeployServicePlugin.removeDeployedWarIfUninstalling(extensions, oieHome));
        assertTrue(Files.exists(war));

        // An uninstall file that lists other extensions must not remove our WAR.
        Files.write(extensions.resolve(ExtensionController.EXTENSIONS_UNINSTALL_FILE),
                "some-other-extension\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(WebClientDeployServicePlugin.removeDeployedWarIfUninstalling(extensions, oieHome));
        assertTrue(Files.exists(war));
    }
}
