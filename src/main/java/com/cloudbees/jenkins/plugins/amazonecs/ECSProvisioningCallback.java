package com.cloudbees.jenkins.plugins.amazonecs;

import hudson.model.Label;
import hudson.model.Node;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class ECSProvisioningCallback implements Callable<Node> {

    protected static final Logger LOGGER = Logger.getLogger(ECSCloud.class.getName());

    protected final ECSTaskTemplate template;
    @CheckForNull
    protected Label label;

    ECSProvisioningCallback(ECSTaskTemplate template, @Nullable Label label) {
        this.template = template;
        this.label = label;
    }

    void runTask(ECSService ecsService, ECSSlave slave, String cluster, String jenkinsUrl, String tunnel) throws IOException {
        try {
            String taskDefinitionArn = ecsService.registerTemplate(slave.getCloud(), template, cluster);
            String taskArn = ecsService.runEcsTask(slave, template, cluster, getDockerRunCommand(slave, jenkinsUrl, tunnel), taskDefinitionArn);
            LOGGER.log(Level.INFO, "Slave {0} - Slave Task Started : {1}",
                    new Object[] {slave.getNodeName(), taskArn});
            slave.setTaskArn(taskArn);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Slave {0} - Cannot create ECS Task", new Object[]{slave.getNodeName()});
            Jenkins.getInstance().removeNode(slave);
            throw ex;
        }
    }

    private Collection<String> getDockerRunCommand(ECSSlave slave, String jenkinsUrl, String tunnel) {
        Collection<String> command = new ArrayList<String>();
        command.add("-url");
        command.add(jenkinsUrl);
        if (StringUtils.isNotBlank(tunnel)) {
            command.add("-tunnel");
            command.add(tunnel);
        }
        command.add(slave.getComputer().getJnlpMac());
        command.add(slave.getComputer().getName());
        return command;
    }

    void waitForSlaveToBeOnline(ECSSlave slave, Date now, Date timeout) throws InterruptedException, IOException {
        while (timeout.after(new Date())) {
            if (slave.getComputer() == null) {
                throw new IllegalStateException(
                    "Slave " + slave.getNodeName() + " - Node was deleted, computer is null");
            }
            if (slave.getComputer().isOnline()) {
                break;
            }
            LOGGER.log(Level.FINE, "Waiting for slave {0} (ecs task {1}) to connect since {2}.",
                new Object[] {slave.getNodeName(), slave.getTaskArn(), now});
            Thread.sleep(1000);
        }
        if (!slave.getComputer().isOnline()) {
            final String msg = MessageFormat.format("ECS Slave {0} (ecs task {1}) not connected since {2} seconds",
                slave.getNodeName(), slave.getTaskArn(), now);
            LOGGER.log(Level.WARNING, msg);
            Jenkins.getInstance().removeNode(slave);
            throw new IllegalStateException(msg);
        }

        LOGGER.log(Level.INFO, "ECS Slave " + slave.getNodeName() + " (ecs task {0}) connected", slave.getTaskArn());
    }
}
