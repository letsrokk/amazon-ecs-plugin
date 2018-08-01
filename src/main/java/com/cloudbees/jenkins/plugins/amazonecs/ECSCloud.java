package com.cloudbees.jenkins.plugins.amazonecs;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.regions.Regions;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import hudson.model.Node;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

abstract class ECSCloud extends Cloud {

    private static final int DEFAULT_SLAVE_TIMEOUT = 900;

    protected static final Logger LOGGER = Logger.getLogger(ECSCloud.class.getName());

    /**
     * Id of the {@link AmazonWebServicesCredentials} used to connect to Amazon ECS
     */
    protected String credentialsId;
    protected String regionName;
    protected String cluster;

    /**
     * Tunnel connection through
     */
    @CheckForNull
    protected String tunnel;

    protected String jenkinsUrl;

    protected List<ECSTaskTemplate> templates;

    protected int slaveTimeoutInSeconds;

    ECSCloud(
            @Nonnull String name,
            @Nonnull String credentialsId,
            @Nonnull String regionName,
            @Nonnull String cluster,
            String jenkinsUrl,
            List<ECSTaskTemplate> templates,
            Integer slaveTimeoutInSeconds) {
        super(name);
        this.credentialsId = credentialsId;
        this.cluster = cluster;
        this.regionName = regionName;

        if (StringUtils.isNotBlank(jenkinsUrl)) {
            this.jenkinsUrl = jenkinsUrl;
        } else {
            this.jenkinsUrl = JenkinsLocationConfiguration.get().getUrl();
        }

        this.templates = templates;

        if (slaveTimeoutInSeconds > 0) {
            this.slaveTimeoutInSeconds = slaveTimeoutInSeconds;
        } else {
            this.slaveTimeoutInSeconds = DEFAULT_SLAVE_TIMEOUT;
        }
    }

    abstract String getCredentialsId();

    @DataBoundSetter
    abstract void setCredentialsId(String credentialsId);

    abstract String getCluster();

    @DataBoundSetter
    abstract void setCluster(String cluster);

    abstract String getRegionName();

    @DataBoundSetter
    abstract void setRegionName(String regionName);

    abstract String getJenkinsUrl();

    @DataBoundSetter
    abstract void setJenkinsUrl(String jenkinsUrl);

    abstract String getTunnel();

    @DataBoundSetter
    abstract void setTunnel(String tunnel);

    abstract List<ECSTaskTemplate> getTemplates();

    abstract int getSlaveTimeoutInSeconds();

    @DataBoundSetter
    abstract void setSlaveTimeoutInSeconds(int slaveTimeoutInSeconds);

    /**
     *  Common methods
     */

    abstract void deleteTask(String taskArn, String clusterArn);

    public static List<ECSSlave> getECSSlaves() {
        final Jenkins jenkins = Jenkins.get();
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

    protected synchronized ECSService getEcsService() {
        return AWSClientsManager.getEcsService(credentialsId, regionName);
    }

    protected static Region getRegion(String regionName) {
        if (StringUtils.isNotEmpty(regionName)) {
            return RegionUtils.getRegion(regionName);
        } else {
            return Region.getRegion(Regions.US_EAST_1);
        }
    }

    public static ECSCloud get() {
        return Jenkins.get().clouds.get(ECSCloud.class);
    }
}
