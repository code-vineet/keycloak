/*
 * Copyright 2014 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.keycloak.subsystem.server.extension.authserver;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;

import java.util.Set;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Base class for operations that create overlays for an auth server.
 *
 * @author Stan Silvert ssilvert@redhat.com (C) 2014 Red Hat Inc.
 */
public abstract class AbstractAddOverlayHandler extends AbstractAddStepHandler{

    protected static final String UPLOADED_FILE_OP_NAME = "uploaded-file-name";
    protected static final SimpleAttributeDefinition UPLOADED_FILE_NAME =new SimpleAttributeDefinitionBuilder(UPLOADED_FILE_OP_NAME, ModelType.STRING, false)
            .setAllowExpression(false)
            .setAllowNull(false)
            .build();

    protected static final SimpleAttributeDefinition BYTES_TO_UPLOAD= new SimpleAttributeDefinitionBuilder("bytes-to-upload", ModelType.BYTES, false)
            .setAllowExpression(false)
            .build();

    static final SimpleAttributeDefinition REDEPLOY_SERVER =
        new SimpleAttributeDefinitionBuilder("redeploy", ModelType.BOOLEAN, true)
        .setAllowExpression(true)
        .setDefaultValue(new ModelNode(false))
        .build();

    protected static final SimpleAttributeDefinition OVERWRITE =
        new SimpleAttributeDefinitionBuilder("overwrite", ModelType.BOOLEAN, true)
        .setAllowExpression(true)
        .setDefaultValue(new ModelNode(false))
        .build();

    public AbstractAddOverlayHandler() {
        super(AddProviderHandler.DEFINITION.getParameters());
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
        final String uploadFileName = UPLOADED_FILE_NAME.resolveModelAttribute(context, model).asString();
        final boolean isRedeploy =  isRedeploy(context, operation);
        final boolean isOverwrite = OVERWRITE.resolveModelAttribute(context, model).asBoolean();

        String overlayPath = getOverlayPath(uploadFileName);
        String overlayName = AuthServerUtil.getOverlayName(operation);
        PathAddress overlayAddress = AuthServerUtil.getOverlayAddress(overlayName);
        String deploymentName = AuthServerUtil.getDeploymentName(operation);

        boolean isOverlayExists = AuthServerUtil.isOverlayExists(context, overlayName, PathAddress.EMPTY_ADDRESS);
        if (!isOverlayExists) {
            addOverlay(context, overlayAddress);
            if (!isHostController(context)) {
                addDeploymentToOverlay(context, overlayAddress, deploymentName);
            }
        }

        if (isHostController(context)) {
            addOverlayToServerGroups(context, overlayAddress, operation, overlayName);
        }

        if (isOverlayExists && isContentExists(context, overlayAddress, overlayPath)) {
            if (isOverwrite) {
                removeContent(context, overlayAddress, overlayPath);
            } else {
                throw new OperationFailedException(pathExistsMessage(overlayAddress, overlayPath));
            }
        }

        addContent(context, overlayAddress, BYTES_TO_UPLOAD.resolveModelAttribute(context, model).asBytes(), overlayPath);

        if (isRedeploy) { AuthServerUtil.addStepToRedeployAuthServer(context, deploymentName); }
        if (!isRedeploy) { context.restartRequired(); }
        context.completeStep(OperationContext.ResultHandler.NOOP_RESULT_HANDLER);
    }

    static void removeContent(OperationContext context, PathAddress overlayAddress, String overlayPath) {
        PathAddress contentAddress = overlayAddress.append("content", overlayPath);
        ModelNode operation = Util.createRemoveOperation(contentAddress);
        context.addStep(operation, AuthServerUtil.getHandler(context, contentAddress, REMOVE), OperationContext.Stage.MODEL);
    }

    static boolean isRedeploy(OperationContext context, ModelNode model) throws OperationFailedException {
        return isAuthServerEnabled(context) && REDEPLOY_SERVER.resolveModelAttribute(context, model).asBoolean();
    }

    private boolean isHostController(OperationContext context) {
        return context.getProcessType() == ProcessType.HOST_CONTROLLER;
    }

    private String pathExistsMessage(PathAddress overlayAddress, String overlayPath) {
        PathAddress contentAddress = overlayAddress.append("content", overlayPath);
        String msg = "Can not update overlay at " + contentAddress.toCLIStyleString();
        msg += "  You may try your request again using the " + OVERWRITE.getName() + " attribute.";
        return msg;
    }

    private boolean isContentExists(OperationContext context, PathAddress overlayAddress, String overlayPath) {
        Resource resource = context.readResourceFromRoot(overlayAddress);
        return resource.getChildrenNames("content").contains(overlayPath);
    }

    private void addOverlay(OperationContext context, PathAddress overlayAddress) {
        ModelNode op = Util.createAddOperation(overlayAddress);
        doAddStep(context, overlayAddress, op);
    }

    private void addDeploymentToOverlay(OperationContext context, PathAddress overlayAddress, String deploymentName) {
        PathAddress deploymentAddress = overlayAddress.append("deployment", deploymentName);
        ModelNode op = Util.createAddOperation(deploymentAddress);
        doAddStep(context, deploymentAddress, op);
    }

    // only call this if context.getProcessType() == ProcessType.HOST_CONTROLLER
    private void addOverlayToServerGroups(OperationContext context, PathAddress overlayAddress, ModelNode operation, String overlayName) {
        String myProfile = context.getCurrentAddressValue();
        for (String serverGroup : getServerGroupNames(context)) {
            PathAddress address = PathAddress.pathAddress("server-group", serverGroup);
            ModelNode serverGroupModel = context.readResourceFromRoot(address).getModel();
            if (serverGroupModel.get("profile").asString().equals(myProfile)) {
                PathAddress serverGroupOverlayAddress = address.append(overlayAddress);
                boolean isOverlayExists = AuthServerUtil.isOverlayExists(context, overlayName, address);
                if (!isOverlayExists) {
                    addOverlay(context, serverGroupOverlayAddress);
                    addDeploymentToOverlay(context, serverGroupOverlayAddress, AuthServerUtil.getDeploymentName(operation));
                }
            }
        }
    }

    private Set<String> getServerGroupNames(OperationContext context) {
        return context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS).getChildrenNames("server-group");
    }

    private void addContent(OperationContext context, PathAddress overlayAddress, byte[] bytes, String overlayPath) throws OperationFailedException {
        PathAddress contentAddress = overlayAddress.append("content", overlayPath);
        ModelNode op = Util.createAddOperation(contentAddress);

        ModelNode content = new ModelNode();
        content.get("bytes").set(bytes);
        op.get("content").set(content);

        doAddStep(context, contentAddress, op);
    }

    private void doAddStep(OperationContext context, PathAddress address, ModelNode operation) {
        //System.out.println("**** Adding Add Step ****");
        //System.out.println(scrub(operation).toString());
        context.addStep(operation, AuthServerUtil.getHandler(context, address, ADD), OperationContext.Stage.MODEL);
    }

    private static boolean isAuthServerEnabled(OperationContext context) throws OperationFailedException {
        ModelNode authServerModel = context.readResource(PathAddress.EMPTY_ADDRESS).getModel().clone();
        return AuthServerDefinition.ENABLED.resolveModelAttribute(context, authServerModel).asBoolean();
    }

    // used for debugging
    private ModelNode scrub(ModelNode op) {
        ModelNode scrubbed = op.clone();
        if (scrubbed.has("content")) {
            scrubbed.get("content").set("BYTES REMOVED FOR DISPLAY");
        }
        if (scrubbed.has("bytes-to-upload")) {
            scrubbed.get("bytes-to-upload").set("BYTES REMOVED FOR DISPLAY");
        }
        return scrubbed;
    }

    /**
     * Get the WAR path where the overlay will live.
     *
     * @param fileName The name of the file being uploaded.
     * @return The overlay path as a String.
     */
    abstract String getOverlayPath(String fileName);
}
