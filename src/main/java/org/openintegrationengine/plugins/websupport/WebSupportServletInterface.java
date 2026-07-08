/*
 * OIE Web Support
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.websupport;

import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The engine-side endpoints the web administrator needs beyond the core REST API, shipped as a
 * plugin so the engine itself requires no changes. Three capabilities:
 *
 * 1) Message serialization through the engine's own data type serializers — the exact
 *    toXML()/toJSON() the runtime produces — so a browser can build message trees identical to
 *    the runtime {@code msg}/{@code tmp}, decorated with the data type's vocabulary descriptions.
 * 2) Server-side JavaScript validation with the engine's Rhino, matching runtime compilation.
 * 3) Discovery and static serving of installed extensions' web UI halves ({@code webadmin/}
 *    folders), so a plugin's browser UI follows the engine it is installed on.
 *
 * All operations require only a valid session (any authenticated user) and are not audited:
 * they are read-only helpers hit continuously while editing.
 */
@Path("/extensions/websupport")
@Tag(name = "Extension Services")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface WebSupportServletInterface extends BaseServletInterface {

    public static final String PLUGIN_POINT = "Web Support";

    @POST
    @Path("/datatypes/_serialize")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Serializes a message via the given data type's serializer. Returns { format, data, meta: { root, descriptions } }.")
    @MirthOperation(name = "serializeMessage", display = "Serialize message for data type", auditable = false)
    public Response serializeMessage(// @formatter:off
            // dataType is a query param (not a path segment) so values containing a slash — e.g. "EDI/X12" — pass cleanly.
            @Param("dataType") @Parameter(description = "The data type name (HL7V2, XML, JSON, EDI/X12, NCPDP, DELIMITED, RAW, DICOM, HL7V3).", required = true) @QueryParam("dataType") String dataType,
            @Param("props") @Parameter(description = "Optional serialization-property overrides as newline-separated key=value pairs (e.g. useStrictParser=true).", required = false) @QueryParam("props") String props,
            @Param("message") String message) throws ClientException;
    // @formatter:on

    @POST
    @Path("/javascript/_validate")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Validates (compiles) a JavaScript script with the engine's Rhino. Returns { error: string|null }.")
    @MirthOperation(name = "validateScript", display = "Validate JavaScript", auditable = false)
    public Response validateScript(@Param("script") String script) throws ClientException;

    @GET
    @Path("/webplugins")
    @Operation(summary = "Returns the install-directory paths of all enabled extensions that ship a web administrator UI (i.e. contain webadmin/plugin.json).")
    @MirthOperation(name = "getWebPluginPaths", display = "Get web plugin paths", auditable = false)
    public List<String> getWebPluginPaths() throws ClientException;

    @GET
    @Path("/webplugins/{extensionPath}/{resourcePath:.*}")
    @Produces(MediaType.WILDCARD)
    @Operation(summary = "Serves a static file from an extension's webadmin/ folder (the browser half of the plugin).")
    @MirthOperation(name = "getWebPluginResource", display = "Get web plugin resource", auditable = false)
    public Response getWebPluginResource(// @formatter:off
            @Param("extensionPath") @Parameter(description = "The extension's install-directory name.", required = true) @PathParam("extensionPath") String extensionPath,
            @Param("resourcePath") @Parameter(description = "The file path within the extension's webadmin/ folder.", required = true) @PathParam("resourcePath") String resourcePath) throws ClientException;
    // @formatter:on
}
