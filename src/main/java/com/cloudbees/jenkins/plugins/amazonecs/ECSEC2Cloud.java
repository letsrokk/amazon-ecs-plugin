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
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.identitymanagement.model.ListRolesResult;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.ListClustersRequest;
import com.amazonaws.services.ecs.model.ListClustersResult;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class ECSEC2Cloud extends ECSCloud {

    private static final Logger LOGGER = Logger.getLogger(ECSCloud.class.getName());

    private static final int DEFAULT_SLAVE_TIMEOUT = 900;

    /**
     * Start auto scaling ECS clusters as part of Jenkins initialization.
     *
     * @throws InterruptedException InterruptedException maybe thrown by Slave termination command
     * @throws IOException IOException maybe thrown by Slave termination command
     */

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void init() throws InterruptedException, IOException {
        final Jenkins jenkins = Jenkins.getInstance();

        // Remove all slaves that were persisted when Jenkins shutdown.
        for (final ECSSlave ecsSlave : getSlaves()) {
            LOGGER.log(Level.INFO, "Terminating ECS slave {0}", new Object[] {ecsSlave.getDisplayName()});
            ecsSlave.terminate();
        }

        // Start auto scale in
        for (final Cloud c : jenkins.clouds) {
            if (c instanceof ECSEC2Cloud) {
                final ECSEC2Cloud ecsCloud = (ECSEC2Cloud)c;
                ecsCloud.startAutoScaleIn();
            }
        }
    }

    public static List<ECSSlave> getSlaves() {
        final Jenkins jenkins = Jenkins.getInstance();
        final List<ECSSlave> ecsSlaves = new ArrayList<>();
        final List<Node> allSlaves = jenkins.getNodes();
        for (final Node n : allSlaves) {
            if (n instanceof ECSSlave) {
                final ECSSlave ecsSlave = (ECSSlave)n;
                ecsSlaves.add(ecsSlave);
            }
        }
        return ecsSlaves;
    }

    public static List<ECSSlave> getSlavesOfCloud(final ECSEC2Cloud ecsCloud) {
        final List<ECSSlave> resultSlaves = new ArrayList<>();
        for (final ECSSlave ecsSlave : getSlaves()) {
            if (ecsSlave.getCloud() == ecsCloud) {
                resultSlaves.add(ecsSlave);
            }
        }
        return resultSlaves;
    }

    public static ECSEC2Cloud get() {
        return Hudson.getInstance().clouds.get(ECSEC2Cloud.class);
    }

    private final List<ECSTaskTemplate> templates;

    private String autoScalingGroup;

    private transient ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    private String regionName;

    /**
     * Tunnel connection through
     */
    @CheckForNull
    private String tunnel;

    private String jenkinsUrl;

    private int slaveTimoutInSeconds;

    @DataBoundConstructor
    public ECSEC2Cloud(
            @Nonnull String name,
            List<ECSTaskTemplate> templates,
            @Nonnull String credentialsId,
            @Nonnull String cluster,
            @Nonnull String autoScalingGroup,
            @Nonnull String regionName,
            String jenkinsUrl,
            int slaveTimeoutInSeconds
    ) {
        super(name, credentialsId, regionName, cluster);
        this.autoScalingGroup = autoScalingGroup;
        this.templates = templates;
        this.regionName = regionName;
        LOGGER.log(Level.INFO, "Create ECS cloud {0} on ECS cluster {1} on the region {2}", new Object[] {name, cluster, regionName});

        if (StringUtils.isNotBlank(jenkinsUrl)) {
            this.jenkinsUrl = jenkinsUrl;
        } else {
            Optional<JenkinsLocationConfiguration> jenkinsLocationConfiguration =
                    Optional.ofNullable(JenkinsLocationConfiguration.get());
            if(jenkinsLocationConfiguration.isPresent())
                this.jenkinsUrl = jenkinsLocationConfiguration.get().getUrl();
            else
                this.jenkinsUrl = "http://localhost:8080/";
        }

        if (slaveTimeoutInSeconds > 0) {
            this.slaveTimoutInSeconds = slaveTimeoutInSeconds;
        } else {
            this.slaveTimoutInSeconds = DEFAULT_SLAVE_TIMEOUT;
        }

        startAutoScaleIn();
    }

    private void startAutoScaleIn() {
        if (!StringUtils.isEmpty(autoScalingGroup) && !StringUtils.isEmpty(getCluster())) {
            scheduledExecutorService.scheduleWithFixedDelay(
                    new ECSClusterScaleIn(getEcsService(), getCluster(), autoScalingGroup),
                    5,
                    60,
                    TimeUnit.SECONDS
            );
        }
    }

    AmazonECSClient getAmazonECSClient() {
        return getEcsService().getAmazonECSClient();
    }

    public List<ECSTaskTemplate> getTemplates() {
        return templates;
    }

    public String getAutoScalingGroup() {
        return autoScalingGroup;
    }

    public String getTunnel() {
        return tunnel;
    }

    @DataBoundSetter
    public void setTunnel(String tunnel) {
        this.tunnel = tunnel;
    }

    @CheckForNull
    private static AmazonWebServicesCredentials getCredentials(@Nullable String credentialsId) {
        return AWSCredentialsHelper.getCredentials(credentialsId, Jenkins.getActiveInstance());
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label) != null;
    }

    private ECSTaskTemplate getTemplate(Label label) {
        if (label == null || templates == null) {
            return null;
        }
        for (ECSTaskTemplate t : templates) {
            if (label.matches(t.getLabelSet())) {
                return t;
            }
        }
        return null;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        try {
			LOGGER.log(Level.INFO, "Asked to provision {0} slave(s) for: {1}", new Object[]{excessWorkload, label});

            List<NodeProvisioner.PlannedNode> r = new ArrayList<>();
            final ECSTaskTemplate template = getTemplate(label);

            for (int i = 1; i <= excessWorkload; i++) {
				LOGGER.log(Level.INFO, "Will provision {0}, for label: {1}", new Object[]{template.getDisplayName(), label} );

                r.add(new NodeProvisioner.PlannedNode(template.getDisplayName(), Computer.threadPoolForRemoting.submit(new ProvisioningCallback(template, label)), 1));
            }
            return r;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to provision ECS slave", e);
            return Collections.emptyList();
        }
    }

    void deleteTask(String taskArn, String clusterArn) {
        getEcsService().deleteTask(taskArn, clusterArn);
    }

    public int getSlaveTimoutInSeconds() {
        return slaveTimoutInSeconds;
    }

    public void setSlaveTimoutInSeconds(int slaveTimoutInSeconds) {
        this.slaveTimoutInSeconds = slaveTimoutInSeconds;
    }

    private class ProvisioningCallback extends ECSProvisioningCallback {

        ProvisioningCallback(ECSTaskTemplate template, @Nullable Label label) {
            super(template, label);
        }

        @Override
        public Node call() throws Exception {
            final ECSSlave slave;

            Date now = new Date();
            Date timeout = new Date(now.getTime() + 1000 * slaveTimoutInSeconds);

            synchronized (getCluster()) {
                getEcsService().waitForSufficientClusterResources(timeout, template, getCluster(), autoScalingGroup);
            }

            String uniq = Long.toHexString(System.nanoTime());
            slave = new ECSSlave(
                    ECSEC2Cloud.this,
                    name + "-" + uniq,
                    template.getRemoteFSRoot(),
                    label == null ? null : label.toString(),
                    new JNLPLauncher()
            );
            slave.setClusterArn(getCluster());
            Jenkins.getInstance().addNode(slave);
            LOGGER.log(Level.INFO, "Created Slave: {0}", slave.getNodeName());

            runTask(getEcsService(), slave, getCluster(), jenkinsUrl, tunnel);
            waitForSlaveToBeOnline(slave, now, timeout);

            return slave;
        }

    }

    @Extension
    public static class DescriptorImpl extends ECSCloudDescriptor {

        public ListBoxModel doFillAutoScalingGroupItems(@QueryParameter String credentialsId, @QueryParameter String regionName) {
            final ECSService ecsService = AWSClientsManager.getEcsService(credentialsId, regionName);
            try {
                final AmazonAutoScalingClient client = ecsService.getAmazonAutoScalingClient();
                final List<AutoScalingGroup> allAutoScalingGroups = new ArrayList<AutoScalingGroup>();
                String lastToken = null;
                do {
                    final DescribeAutoScalingGroupsResult res =
                        client.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withNextToken(lastToken));
                    allAutoScalingGroups.addAll(res.getAutoScalingGroups());
                    lastToken = res.getNextToken();
                } while (lastToken != null);
                final List<String> allAutoScalingGroupNames = new ArrayList<String>();
                for (final AutoScalingGroup asg : allAutoScalingGroups) {
                    allAutoScalingGroupNames.add(asg.getAutoScalingGroupName());
                }
                Collections.sort(allAutoScalingGroupNames);
                final ListBoxModel options = new ListBoxModel();
                options.add("");
                for (final String autoScalingGroupName : allAutoScalingGroupNames) {
                    options.add(autoScalingGroupName);
                }
                return options;
            } catch (AmazonClientException e) {
                // missing credentials will throw an "AmazonClientException: Unable to load AWS credentials from any provider in the chain"
                LOGGER.log(Level.INFO, "Exception searching autoscaling instances for credentials=" + credentialsId + ", regionName=" + regionName + ":" + e);
                LOGGER.log(Level.FINE, "Exception searching autoscaling instances for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            } catch (RuntimeException e) {
                LOGGER.log(Level.INFO, "Exception searching autoscaling instances for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            }
        }

    }

//    public static Region getRegion(String regionName) {
//        if (StringUtils.isNotEmpty(regionName)) {
//            return RegionUtils.getRegion(regionName);
//        } else {
//            return Region.getRegion(Regions.US_EAST_1);
//        }
//    }
//
//    public String getJenkinsUrl() {
//        return jenkinsUrl;
//    }
//
//    public void setJenkinsUrl(String jenkinsUrl) {
//        this.jenkinsUrl = jenkinsUrl;
//    }
}
