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
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class ECSEC2Cloud extends ECSCloud {

    private String autoScalingGroup;

    /**
     * Start auto scaling ECS clusters as part of Jenkins initialization.
     */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void init() {
        // Remove all slaves that were persisted when Jenkins shutdown.
        getECSSlaves().stream()
                .filter(slave -> slave.getCloud() instanceof ECSEC2Cloud)
                .forEach(slave -> {
                    try {
                        LOGGER.log(Level.INFO, "Terminating ECS slave {0}", new Object[]{slave.getDisplayName()});
                        slave.terminate();
                    } catch (InterruptedException | IOException e) {
                        LOGGER.log(Level.SEVERE, String.format("Terminating ECS slave %s", slave.getDisplayName()), e);
                    }
                });

        final Jenkins jenkins = Jenkins.get();
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
            // Start auto scale in
            for (final Cloud c : jenkins.clouds) {
                if (c instanceof ECSEC2Cloud) {
                    final ECSEC2Cloud ecsCloud = (ECSEC2Cloud) c;
                    ecsCloud.startAutoScaleIn();
                }
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

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
        super(name, credentialsId, regionName, cluster, jenkinsUrl, templates, slaveTimeoutInSeconds);
        this.autoScalingGroup = autoScalingGroup;

        LOGGER.log(Level.INFO, "Create ECS cloud {0} on ECS cluster {1} on the region {2}", new Object[]{name, cluster, regionName});

        startAutoScaleIn();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public String getCluster() {
        return cluster;
    }

    void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getRegionName() {
        return regionName;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public String getJenkinsUrl() {
        return jenkinsUrl;
    }

    public void setJenkinsUrl(String jenkinsUrl) {
        this.jenkinsUrl = jenkinsUrl;
    }

    public String getTunnel() {
        return tunnel;
    }

    public void setTunnel(String tunnel) {
        this.tunnel = tunnel;
    }

    public List<ECSTaskTemplate> getTemplates() {
        return templates;
    }

    public int getSlaveTimeoutInSeconds() {
        return slaveTimeoutInSeconds;
    }

    public void setSlaveTimeoutInSeconds(int slaveTimeoutInSeconds) {
        this.slaveTimeoutInSeconds = slaveTimeoutInSeconds;
    }

    public String getAutoScalingGroup() {
        return autoScalingGroup;
    }

    @DataBoundSetter
    public void setAutoScalingGroup(String autoScalingGroup) {
        this.autoScalingGroup = autoScalingGroup;
    }

    /**
     * Utility methods
     */

    private void startAutoScaleIn() {
        if (!StringUtils.isEmpty(autoScalingGroup) && !StringUtils.isEmpty(getCluster())) {
            LOGGER.log(Level.FINE, "Schedule scale in check for ECS cluster {0} (using auto scaling group {1})", new Object[]{getCluster(), autoScalingGroup});
            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            scheduledExecutorService.schedule(
                    new ECSClusterScaleIn(getEcsService(), getCluster(), autoScalingGroup),
                    0, TimeUnit.SECONDS
            );
            scheduledExecutorService.shutdown();
        }
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
                LOGGER.log(Level.INFO, "Will provision {0}, for label: {1}", new Object[]{template.getDisplayName(), label});

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

    private class ProvisioningCallback extends ECSProvisioningCallback {

        ProvisioningCallback(ECSTaskTemplate template, @Nullable Label label) {
            super(template, label);
        }

        @Override
        public Node call() throws Exception {
            final ECSSlave slave;

            Date now = new Date();
            Date timeout = new Date(now.getTime() + 1000 * slaveTimeoutInSeconds);

            synchronized (getCluster()) {
                getEcsService().waitForSufficientClusterResources(timeout, template, getCluster(), autoScalingGroup);
            }

            String uniq = Long.toHexString(System.nanoTime());
            slave = new ECSSlave(
                    ECSEC2Cloud.this,
                    name + "-" + uniq,
                    template.getRemoteFSRoot(),
                    label == null ? null : label.toString(),
                    new JNLPLauncher(true)
            );
            slave.setClusterArn(getCluster());
            Jenkins.get().addNode(slave);
            LOGGER.log(Level.INFO, "Created Slave: {0}", slave.getNodeName());

            runTask(getEcsService(), slave, getCluster(), getJenkinsUrl(), getTunnel());
            waitForSlaveToBeOnline(slave, now, timeout);

            return slave;
        }

    }

    @Extension
    public static class DescriptorImpl extends ECSCloudDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.displayNameEC2();
        }

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

}
