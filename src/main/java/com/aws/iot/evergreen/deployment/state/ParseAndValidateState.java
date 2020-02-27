/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentConfiguration;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ParseAndValidateState extends BaseState {

    private Logger logger = LogManager.getLogger(ParseAndValidateState.class);

    public ParseAndValidateState(DeploymentPacket packet, ObjectMapper objectMapper) {
        this.deploymentPacket = packet;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean canProceed() {
        return true;
    }

    @Override
    public void proceed() throws DeploymentFailureException {
        logger.info("Parsing and validating the job document");
        DeploymentConfiguration deploymentConfiguration =
                parseAndValidateJobDocument(deploymentPacket.getJobDocument());
        logger.atInfo().log("Deployment configuration received in the job is {}",
                deploymentConfiguration.toString());
        deploymentPacket.setDeploymentId(deploymentConfiguration.getDeploymentId());
        deploymentPacket.setDeploymentCreationTimestamp(deploymentConfiguration.getTimestamp());
        Set<PackageMetadata> proposedPackages = getPackageMetadata(deploymentConfiguration);
        deploymentPacket.setProposedPackagesFromDeployment(proposedPackages);
        //cleaning up the space since this is no longer needed in the process
        deploymentPacket.getJobDocument().clear();
    }

    //Unnecessary?
    private Set<PackageMetadata> getPackageMetadata(DeploymentConfiguration deploymentConfiguration) {
        logger.info("Getting package metadata");
        Map<String, DeploymentPackageConfiguration> nameToPackageConfig =
                deploymentConfiguration.getDeploymentPackageConfigurationList().stream()
                        .collect(Collectors.toMap(pkgConfig -> pkgConfig.getPackageName(), pkgConfig -> pkgConfig));
        Map<String, PackageMetadata> nameToPackageMetadata = new HashMap<>();
        nameToPackageConfig.forEach((pkgName, configuration) -> {
            nameToPackageMetadata.put(pkgName,
                    new PackageMetadata(configuration.getPackageName(), configuration.getResolvedVersion(),
                            configuration.getVersionConstraint(), new HashSet<>(), configuration.getParameters()));
        });
        Set<PackageMetadata> packageMetadata = new HashSet<>();
        for (Map.Entry<String, PackageMetadata> entry : nameToPackageMetadata.entrySet()) {
            //Go through all the package metadata and create their dependency trees
            String packageName = entry.getKey();
            PackageMetadata currentPackageMetdata = entry.getValue();
            if (nameToPackageConfig.containsKey(packageName)) {
                DeploymentPackageConfiguration deploymentPackageConfiguration = nameToPackageConfig.get(packageName);
                if (deploymentPackageConfiguration.getListOfDependentPackages() != null) {
                    for (DeploymentPackageConfiguration.NameVersionPair dependencyNameVersion :
                            deploymentPackageConfiguration.getListOfDependentPackages()) {
                        if (nameToPackageMetadata.containsKey(dependencyNameVersion.getPackageName())) {
                            currentPackageMetdata.getDependsOn()
                                    .add(nameToPackageMetadata.get(dependencyNameVersion.getPackageName()));
                        }
                        //TODO: Handle case when package does not exist for a specified dependency
                    }
                }
            }
            if (deploymentConfiguration.getListOfPackagesToDeploy().contains(packageName)) {
                packageMetadata.add(currentPackageMetdata);
            }
        }

        logger.atInfo().log("Set of package metadata derived is {}", packageMetadata.toString());
        return packageMetadata;
    }

    private DeploymentConfiguration parseAndValidateJobDocument(HashMap<String, Object> jobDocument)
            throws DeploymentFailureException {
        if (jobDocument == null) {
            String errorMessage = "Job document cannot be empty";
            throw new DeploymentFailureException(errorMessage);
        }
        DeploymentConfiguration deploymentConfiguration = null;
        try {
            String jobDocumentString = objectMapper.writeValueAsString(jobDocument);
            deploymentConfiguration = objectMapper.readValue(jobDocumentString, DeploymentConfiguration.class);
            return deploymentConfiguration;
        } catch (JsonProcessingException e) {
            String errorMessage = "Unable to parse the job document";
            logger.error(errorMessage, e);
            logger.error(e.getMessage());
            throw new DeploymentFailureException(errorMessage, e);
        }

    }

    @Override
    public void cancel() {

    }
}