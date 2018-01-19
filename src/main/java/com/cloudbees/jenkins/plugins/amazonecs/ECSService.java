/*
 * The MIT License
 *
 *  Copyright (c) 2015, CloudBees, Inc.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package com.cloudbees.jenkins.plugins.amazonecs;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import com.amazonaws.services.ecs.model.*;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.Instance;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;

import hudson.AbortException;
import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;

/**
 * Encapsulates interactions with Amazon ECS.
 *
 * @author Jan Roehrich <jan@roehrich.info>
 * 
 */
class ECSService {

    private static final Logger LOGGER = Logger.getLogger(ECSStandartCloud.class.getName());

    private String credentialsId;

    private String regionName;

    public ECSService(String credentialsId, String regionName) {
        super();
        this.credentialsId = credentialsId;
        this.regionName = regionName;
    }

    ClientConfiguration getClientConfiguration() {
        final ProxyConfiguration proxy = Jenkins.getInstance().proxy;
        final ClientConfiguration clientConfiguration = new ClientConfiguration();
        if (proxy != null) {
            clientConfiguration.setProxyHost(proxy.name);
            clientConfiguration.setProxyPort(proxy.port);
            clientConfiguration.setProxyUsername(proxy.getUserName());
            clientConfiguration.setProxyPassword(proxy.getPassword());
        }
        return clientConfiguration;
    }

    private void logAwsKey(final AmazonWebServicesCredentials credentials, final String awsServiceName) {
        if (credentials != null && LOGGER.isLoggable(Level.FINE)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                final String awsAccessKeyId = credentials.getCredentials().getAWSAccessKeyId();
                final String obfuscatedAccessKeyId = StringUtils.left(awsAccessKeyId, 4)
                    + StringUtils.repeat("*", awsAccessKeyId.length() - 2 * 4) + StringUtils.right(awsAccessKeyId, 4);
                LOGGER.log(Level.FINE, "Connect to Amazon {0} with IAM Access Key {1}",
                    new Object[] {awsServiceName, obfuscatedAccessKeyId});
            }
        }
    }

    AmazonECSClient getAmazonECSClient() {
        final AmazonECSClient client;
        // = AmazonECSClientBuilder.standard().withClientConfiguration(getClientConfiguration())
        // .withCredentials(getCredentials(credentialsId)).build();
        // client.setRegion(getRegion(regionName));

        final ClientConfiguration clientConfiguration = getClientConfiguration();
        final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
        if (credentials == null) {
            // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
            // to use IAM Role define at the EC2 instance level ...
            client = new AmazonECSClient(clientConfiguration);
        } else {
            logAwsKey(credentials, "ECS");
            client = new AmazonECSClient(credentials, clientConfiguration);
        }
        client.setRegion(getRegion(regionName));
        LOGGER.log(Level.FINE, "Selected Region: {0}", regionName);
        return client;
    }

    AmazonAutoScalingClient getAmazonAutoScalingClient() {
        final AmazonAutoScalingClient client;
        final ClientConfiguration clientConfiguration = getClientConfiguration();
        final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
        if (credentials == null) {
            // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
            // to use IAM Role define at the EC2 instance level ...
            client = new AmazonAutoScalingClient(clientConfiguration);
        } else {
            logAwsKey(credentials, "AutoScaling");
            client = new AmazonAutoScalingClient(credentials, clientConfiguration);
        }
        client.setRegion(getRegion(regionName));
        LOGGER.log(Level.FINE, "Selected Region: {0}", regionName);
        return client;
    }

    AmazonEC2Client getAmazonEC2Client() {
        final AmazonEC2Client client;
        final ClientConfiguration clientConfiguration = getClientConfiguration();
        final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
        if (credentials == null) {
            // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
            // to use IAM Role define at the EC2 instance level ...
            client = new AmazonEC2Client(clientConfiguration);
        } else {
            logAwsKey(credentials, "EC2");
            client = new AmazonEC2Client(credentials, clientConfiguration);
        }
        client.setRegion(getRegion(regionName));
        LOGGER.log(Level.FINE, "Selected Region: {0}", regionName);
        return client;
    }

    Region getRegion(String regionName) {
        if (StringUtils.isNotEmpty(regionName)) {
            return RegionUtils.getRegion(regionName);
        } else {
            return Region.getRegion(Regions.US_EAST_1);
        }
    }

    @CheckForNull
    private AmazonWebServicesCredentials getCredentials(@Nullable String credentialsId) {
        return AWSCredentialsHelper.getCredentials(credentialsId, Jenkins.getActiveInstance());
    }

    void deleteTask(String taskArn, String clusterArn) {
        final AmazonECSClient client = getAmazonECSClient();

        LOGGER.log(Level.INFO, "Delete ECS Slave task: {0}", taskArn);
        try {
            client.stopTask(new StopTaskRequest().withTask(taskArn).withCluster(clusterArn));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Couldn't stop task arn " + taskArn + " caught exception: " + e.getMessage(), e);
        }
    }

    /**
     * Looks whether the latest task definition matches the desired one. If yes, returns the ARN of the existing one.
     * If no, register a new task definition with desired parameters and return the new ARN.
     */
    String registerTemplate(final ECSCloud cloud, final ECSTaskTemplate template, String clusterArn) {
        final AmazonECSClient client = getAmazonECSClient();

        String familyName = fullQualifiedTemplateName(cloud, template);
        final ContainerDefinition def = new ContainerDefinition()
            .withName(familyName)
            .withImage(template.getImage())
            .withEnvironment(template.getEnvironmentKeyValuePairs())
            .withExtraHosts(template.getExtraHostEntries())
            .withMountPoints(template.getMountPointEntries())
            .withCpu(template.getCpu())
            .withPrivileged(template.getPrivileged())
            .withEssential(true);

        /*
           at least one of memory or memoryReservation has to be set
           the form validation will highlight if the settings are inappropriate
         */
        if (template.getMemoryReservation() > 0) /* this is the soft limit */
            def.withMemoryReservation(template.getMemoryReservation());

        if (template.getMemory() > 0)/* this is the hard limit */
            def.withMemory(template.getMemory());

        if (template.getEntrypoint() != null)
            def.withEntryPoint(StringUtils.split(template.getEntrypoint()));

        if (template.getJvmArgs() != null)
            def.withEnvironment(new KeyValuePair()
                .withName("JAVA_OPTS").withValue(template.getJvmArgs()))
                .withEssential(true);

        if (template.getLogDriver() != null) {
            LogConfiguration logConfig = new LogConfiguration();
            logConfig.setLogDriver(template.getLogDriver());
            logConfig.setOptions(template.getLogDriverOptionsMap());
            def.withLogConfiguration(logConfig);
        }

        String lastToken = null;
        Deque<String> taskDefinitions = new LinkedList<String>();
        do {
            ListTaskDefinitionsResult listTaskDefinitions = client.listTaskDefinitions(new ListTaskDefinitionsRequest()
                .withFamilyPrefix(familyName)
                .withMaxResults(100)
                .withNextToken(lastToken));
            taskDefinitions.addAll(listTaskDefinitions.getTaskDefinitionArns());
            lastToken = listTaskDefinitions.getNextToken();
        } while (lastToken != null);

        boolean templateMatchesExistingContainerDefinition = false;
        boolean templateMatchesExistingVolumes = false;
        boolean templateMatchesExistingTaskRole = false;
        boolean templateMatchesCompatibility = false;

        DescribeTaskDefinitionResult describeTaskDefinition = null;

        if (taskDefinitions.size() > 0) {
            describeTaskDefinition = client.describeTaskDefinition(new DescribeTaskDefinitionRequest().withTaskDefinition(taskDefinitions.getLast()));

            templateMatchesExistingContainerDefinition = def.equals(describeTaskDefinition.getTaskDefinition().getContainerDefinitions().get(0));
            LOGGER.log(Level.INFO, "Match on container defintion: {0}",new Object[] {templateMatchesExistingContainerDefinition});
            LOGGER.log(Level.FINE, "Match on container defintion: {0}; template={1}; last={2}", new Object[] {templateMatchesExistingContainerDefinition, def, describeTaskDefinition.getTaskDefinition().getContainerDefinitions().get(0)});

            templateMatchesExistingVolumes = ObjectUtils.equals(template.getVolumeEntries(), describeTaskDefinition.getTaskDefinition().getVolumes());
            LOGGER.log(Level.INFO, "Match on volumes: {0}", new Object[] {templateMatchesExistingVolumes});
            LOGGER.log(Level.FINE, "Match on volumes: {0}; template={1}; last={2}", new Object[] {templateMatchesExistingVolumes, template.getVolumeEntries(), describeTaskDefinition.getTaskDefinition().getVolumes()});

            templateMatchesExistingTaskRole = template.getTaskrole() == null || template.getTaskrole().equals(describeTaskDefinition.getTaskDefinition().getTaskRoleArn());
            LOGGER.log(Level.INFO, "Match on task role: {0}", new Object[] {templateMatchesExistingTaskRole});
            LOGGER.log(Level.FINE, "Match on task role: {0}; template={1}; last={2}", new Object[] {templateMatchesExistingTaskRole, template.getTaskrole(), describeTaskDefinition.getTaskDefinition().getTaskRoleArn()});

            templateMatchesCompatibility = describeTaskDefinition.getTaskDefinition().getRequiresCompatibilities().contains(Compatibility.FARGATE.toString());
        }

        templateMatchesCompatibility = false;

        if (templateMatchesExistingContainerDefinition && templateMatchesExistingVolumes && templateMatchesExistingTaskRole && templateMatchesCompatibility) {
            LOGGER.log(Level.FINE, "Task Definition already exists: {0}", new Object[] {describeTaskDefinition.getTaskDefinition().getTaskDefinitionArn()});
            return describeTaskDefinition.getTaskDefinition().getTaskDefinitionArn();
        } else {
            final RegisterTaskDefinitionRequest request = new RegisterTaskDefinitionRequest()
                .withFamily(familyName)
                .withVolumes(template.getVolumeEntries())
                .withContainerDefinitions(def)
                    // IF FARGATE
                    .withCpu("512")
                    .withMemory("1024")
                    .withNetworkMode(NetworkMode.Awsvpc)
                    .withRequiresCompatibilities(Compatibility.FARGATE);

            if (template.getTaskrole() != null) {
                request.withTaskRoleArn(template.getTaskrole());
            }
            final RegisterTaskDefinitionResult result = client.registerTaskDefinition(request);
            String taskDefinitionArn = result.getTaskDefinition().getTaskDefinitionArn();
            LOGGER.log(Level.FINE, "Created Task Definition {0}: {1}", new Object[] {taskDefinitionArn, request});
            LOGGER.log(Level.INFO, "Created Task Definition: {0}", new Object[] {taskDefinitionArn});
            return taskDefinitionArn;
        }
    }

    private String fullQualifiedTemplateName(final ECSCloud cloud, final ECSTaskTemplate template) {
        return cloud.getDisplayName().replaceAll("\\s+", "") + '-' + template.getTemplateName();
    }

    String runEcsTask(final ECSSlave slave, final ECSTaskTemplate template, String clusterArn, Collection<String> command, String taskDefinitionArn) throws IOException, AbortException {
        AmazonECSClient client = getAmazonECSClient();
        slave.setTaskDefinitonArn(taskDefinitionArn);

        KeyValuePair envNodeName = new KeyValuePair();
        envNodeName.setName("SLAVE_NODE_NAME");
        envNodeName.setValue(slave.getComputer().getName());

        KeyValuePair envNodeSecret = new KeyValuePair();
        envNodeSecret.setName("SLAVE_NODE_SECRET");
        envNodeSecret.setValue(slave.getComputer().getJnlpMac());

        final RunTaskResult runTaskResult = client.runTask(new RunTaskRequest()
            .withTaskDefinition(taskDefinitionArn)
            .withOverrides(new TaskOverride()
                .withContainerOverrides(new ContainerOverride()
                    .withName(fullQualifiedTemplateName(slave.getCloud(), template))
                    .withCommand(command)
                    .withEnvironment(envNodeName)
                    .withEnvironment(envNodeSecret)))
                // IF FARGATE
                .withLaunchType(LaunchType.FARGATE)
                .withNetworkConfiguration(new NetworkConfiguration().withAwsvpcConfiguration(
                        new AwsVpcConfiguration()
                                .withAssignPublicIp(AssignPublicIp.DISABLED)
                                .withSubnets("subnet-d8ae3785")
                                .withSecurityGroups("sg-47c18e33")
                ))
            .withCluster(clusterArn)
        );

        if (!runTaskResult.getFailures().isEmpty()) {
            LOGGER.log(Level.WARNING, "Slave {0} - Failure to run task with definition {1} on ECS cluster {2}", new Object[] {slave.getNodeName(), taskDefinitionArn, clusterArn});
            for (Failure failure : runTaskResult.getFailures()) {
                LOGGER.log(Level.WARNING, "Slave {0} - Failure reason={1}, arn={2}", new Object[] {slave.getNodeName(), failure.getReason(), failure.getArn()});
            }
            throw new AbortException("Failed to run slave container " + slave.getNodeName());
        }
        return runTaskResult.getTasks().get(0).getTaskArn();
    }

    void waitForSufficientClusterResources(Date timeout, ECSTaskTemplate template, String clusterArn, String asgName) throws InterruptedException, AbortException {
        AmazonECSClient client = getAmazonECSClient();
        AmazonAutoScalingClient autoScalingClient = null;

        boolean hasEnoughResources = false;
        WHILE:
        do {
            ListContainerInstancesResult listContainerInstances = client.listContainerInstances(new ListContainerInstancesRequest().withCluster(clusterArn));
            if (!listContainerInstances.getContainerInstanceArns().isEmpty()) {
                DescribeContainerInstancesResult containerInstancesDesc = client.describeContainerInstances(new DescribeContainerInstancesRequest().withContainerInstances(listContainerInstances.getContainerInstanceArns()).withCluster(clusterArn));
                LOGGER.log(Level.INFO, "Found {0} instances", containerInstancesDesc.getContainerInstances().size());
                for (ContainerInstance instance : containerInstancesDesc.getContainerInstances()) {
                    LOGGER.log(Level.INFO, "Resources found in instance {1}: {0}", new Object[] {instance.getRemainingResources(), instance.getContainerInstanceArn()});
                    Resource memoryResource = null;
                    Resource cpuResource = null;
                    for (Resource resource : instance.getRemainingResources()) {
                        if ("MEMORY".equals(resource.getName())) {
                            memoryResource = resource;
                        } else if ("CPU".equals(resource.getName())) {
                            cpuResource = resource;
                        }
                    }

                    LOGGER.log(Level.INFO, "Instance {0} has {1}mb of free memory. {2}mb are required", new Object[] {instance.getContainerInstanceArn(), memoryResource.getIntegerValue(), template.getMemoryConstraint()});
                    LOGGER.log(Level.INFO, "Instance {0} has {1} units of free cpu. {2} units are required", new Object[] {instance.getContainerInstanceArn(), cpuResource.getIntegerValue(), template.getCpu()});
                    if (memoryResource.getIntegerValue() >= template.getMemoryConstraint()
                        && cpuResource.getIntegerValue() >= template.getCpu()) {
                        hasEnoughResources = true;
                        break WHILE;
                    }
                }
            }

            // not enough free resources -> scale out
            if (StringUtils.isNotEmpty(asgName)) {
                if (autoScalingClient == null) {
                    autoScalingClient = getAmazonAutoScalingClient();
                }
                final DescribeAutoScalingGroupsResult describeAutoScalingGroupResult = autoScalingClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asgName));
                for (final AutoScalingGroup autoScalingGroup : describeAutoScalingGroupResult.getAutoScalingGroups()) {
                    final int currentDesiredInstances = autoScalingGroup.getDesiredCapacity();
                    LOGGER.log(Level.INFO, "Auto scaling group {0} currently has {1} instances", new Object[] {asgName, currentDesiredInstances});
                    final int newDesiredInstances = currentDesiredInstances + 1;
                    if (newDesiredInstances <= autoScalingGroup.getMaxSize()) {
                        final int newRunningInstances = waitForScaleOut(timeout, newDesiredInstances, clusterArn, autoScalingGroup.getAutoScalingGroupName());
                        if (newRunningInstances == newDesiredInstances) {
                            hasEnoughResources = true;
                        }
                        break WHILE;
                    } else {
                        LOGGER.log(Level.INFO, "Not increasing size of auto scaling group {0} (already at max size of {1} instances)", new Object[] {asgName, autoScalingGroup.getMaxSize()});
                    }
                }
            }

            // sleep 10s and check memory again
            Thread.sleep(10000);
        } while (!hasEnoughResources && timeout.after(new Date()));

        if (!hasEnoughResources) {
            final String msg = MessageFormat.format("Timeout while waiting for sufficient resources: {0} cpu units, {1}mb free memory", template.getCpu(), template.getMemoryConstraint());
            LOGGER.log(Level.WARNING, msg);
            throw new AbortException(msg);
        }
    }

    private int waitForScaleOut(final Date timeout, final int newDesiredInstances, final String ecsClusterArn,
        final String autoScalingGroupName) throws InterruptedException {
        final int autoScalingInstanceCount = waitForAutoScalingScaleOut(timeout, autoScalingGroupName, newDesiredInstances);
        if (autoScalingInstanceCount == newDesiredInstances) {
            return waitForEcsClusterScaleOut(timeout, ecsClusterArn, newDesiredInstances);
        } else {
            return autoScalingInstanceCount;
        }
    }

    private int waitForAutoScalingScaleOut(final Date timeout, final String autoScalingGroupName, final int desiredCapacity)
        throws InterruptedException {
        final AmazonAutoScalingClient autoScalingClient = getAmazonAutoScalingClient();
        LOGGER.log(Level.INFO, "Increasing size of auto scaling group {0} to {1} instances", new Object[] {autoScalingGroupName, desiredCapacity});
        autoScalingClient.updateAutoScalingGroup(new UpdateAutoScalingGroupRequest().withAutoScalingGroupName(autoScalingGroupName).withDesiredCapacity(desiredCapacity));
        do {
            final int runningInstances = getAutoScalingInServiceCount(autoScalingClient, autoScalingGroupName);
            if (runningInstances == desiredCapacity) {
                LOGGER.log(Level.INFO, "Auto scaling group {0} now has {1} instances", new Object[] {autoScalingGroupName, desiredCapacity});
                return runningInstances;
            } else {
                LOGGER.log(Level.FINE, "Waiting for scale out of auto scaling group {0}", new Object[] {autoScalingGroupName});
                Thread.sleep(5000);
            }
        } while (timeout.after(new Date()));
        return getAutoScalingInServiceCount(autoScalingClient, autoScalingGroupName);
    }

    private int getAutoScalingInServiceCount(final AmazonAutoScalingClient autoScalingClient,
        final String autoScalingGroupName) {
        int inService = 0;
        final List<AutoScalingGroup> autoScalingGroups = autoScalingClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(autoScalingGroupName)).getAutoScalingGroups();
        for (final AutoScalingGroup asg : autoScalingGroups) {
            for (final Instance instance : asg.getInstances()) {
                if (instance.getLifecycleState().equals("InService")) {
                    inService += 1;
                }
            }
        }
        return inService;
    }

    private int waitForEcsClusterScaleOut(final Date timeout, final String ecsClusterArn, final int expectedRunningCount)
        throws InterruptedException {
        final AmazonECSClient ecsClient = getAmazonECSClient();
        do {
            final int activeEcsInstances = getEcsRunningCount(ecsClient, ecsClusterArn);
            if (activeEcsInstances == expectedRunningCount) {
                LOGGER.log(Level.INFO, "ECS cluster {0} now has {1} active instances", new Object[] {ecsClusterArn, activeEcsInstances});
                return activeEcsInstances;
            } else {
                LOGGER.log(Level.FINE, "Waiting for scale out of ECS cluster {0}", new Object[] {ecsClusterArn});
                Thread.sleep(5000);
            }
        } while (timeout.after(new Date()));
        return getEcsRunningCount(ecsClient, ecsClusterArn);
    }

    private int getEcsRunningCount(final AmazonECSClient ecsClient, final String ecsClusterArn) {
        return ecsClient.listContainerInstances(new ListContainerInstancesRequest()
            .withCluster(ecsClusterArn)
            .withStatus(ContainerInstanceStatus.ACTIVE)
        ).getContainerInstanceArns().size();
    }
}
