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
 * <p>Web Support ships only the engine-side APIs the web administrator needs
 * (message serialization, JavaScript validation, and web-plugin asset serving);
 * it performs no server-side deployment. Hosting the browser client on this
 * engine is a separate, optional extension — see
 * {@link WebClientDeployServicePlugin}.</p>
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
