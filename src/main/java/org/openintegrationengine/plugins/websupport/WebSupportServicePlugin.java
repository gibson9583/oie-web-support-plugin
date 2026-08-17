/*
 * OIE Web Support
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.websupport;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;

/**
 * Service plugin for the Web Support REST endpoints.
 *
 * <p>Web Support ships the engine-side APIs the web administrator needs. The
 * companion {@link WebClientDeployServicePlugin} in this same extension installs
 * the bundled browser client into the engine's web server.</p>
 */
public class WebSupportServicePlugin implements ServicePlugin {

    @Override
    public void init(Properties properties) {
        // The REST endpoints are contributed by the servlet interface/provider;
        // there is no server-side initialization to perform here.
    }

    @Override
    public void update(Properties properties) {
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
        return WebSupportServletInterface.PLUGIN_POINT;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
