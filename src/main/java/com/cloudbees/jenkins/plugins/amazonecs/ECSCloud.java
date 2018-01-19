package com.cloudbees.jenkins.plugins.amazonecs;

import hudson.slaves.Cloud;

abstract class ECSCloud extends Cloud {

    protected ECSCloud(String name) {
        super(name);
    }

    abstract void deleteTask(String taskArn, String clusterArn);
}
