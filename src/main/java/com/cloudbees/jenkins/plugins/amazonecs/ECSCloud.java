package com.cloudbees.jenkins.plugins.amazonecs;

import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import hudson.slaves.Cloud;

import javax.annotation.Nonnull;

abstract class ECSCloud extends Cloud {

    /**
     * Id of the {@link AmazonWebServicesCredentials} used to connect to Amazon ECS
     */
    private final String credentialsId;
    private String regionName;
    private final String cluster;

    ECSCloud(
            @Nonnull String name,
            @Nonnull String credentialsId,
            @Nonnull String regionName,
            @Nonnull String cluster
    ) {
        super(name);
        this.credentialsId = credentialsId;
        this.cluster = cluster;
        this.regionName = regionName;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getCluster() {
        return cluster;
    }

    public String getRegionName() {
        return regionName;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public synchronized ECSService getEcsService() {
        return AWSClientsManager.getEcsService(credentialsId, regionName);
    }

    abstract void deleteTask(String taskArn, String clusterArn);

}
