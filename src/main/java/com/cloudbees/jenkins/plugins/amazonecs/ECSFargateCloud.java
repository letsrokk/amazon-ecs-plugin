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

import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.ListClustersRequest;
import com.amazonaws.services.ecs.model.ListClustersResult;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.identitymanagement.model.ListRolesResult;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.*;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class ECSFargateCloud extends ECSCloud {

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
        // Remove all slaves that were persisted when Jenkins shutdown.
        for (final ECSSlave ecsSlave : getSlaves()) {
            LOGGER.log(Level.INFO, "Terminating ECS slave {0}", new Object[] {ecsSlave.getDisplayName()});
            ecsSlave.terminate();
        }
    }

    public static List<ECSSlave> getSlaves() {
        final Jenkins jenkins = Jenkins.getInstance();
        final List<ECSSlave> ecsSlaves = new ArrayList<>();
        final List<Node> allSlaves = jenkins.getNodes();
        for (final Node n : allSlaves) {
            if (n instanceof ECSSlave) {
                ecsSlaves.add((ECSSlave)n);
            }
        }
        return ecsSlaves;
    }

    public static List<ECSSlave> getSlavesOfCloud(final ECSFargateCloud ecsCloud) {
        final List<ECSSlave> resultSlaves = new ArrayList<>();
        for (final ECSSlave ecsSlave : getSlaves()) {
            if (ecsSlave.getCloud() == ecsCloud) {
                resultSlaves.add(ecsSlave);
            }
        }
        return resultSlaves;
    }

    public static ECSFargateCloud get() {
        return Jenkins.getInstance().clouds.get(ECSFargateCloud.class);
    }

    private final List<ECSTaskTemplate> templates;

    private final String vpcId;

    private final String subnetId;

    private final String securityGroup;

    private final String memory;

    private final String cpu;

    /**
     * Tunnel connection through
     */
    @CheckForNull
    private String tunnel;

    private String jenkinsUrl;

    private int slaveTimoutInSeconds;

    private ECSService ecsService;

    @DataBoundConstructor
    public ECSFargateCloud(
            String name,
            List<ECSTaskTemplate> templates,
            @Nonnull String credentialsId,
            String cluster,
            String vpcId,
            String subnetId,
            String securityGroup,
            String regionName,
            String memory,
            String cpu,
            String jenkinsUrl,
            int slaveTimoutInSeconds
    ) {
        super(name, credentialsId, regionName, cluster);
        this.vpcId = vpcId;
        this.subnetId = subnetId;
        this.securityGroup = securityGroup;
        this.templates = templates;
        this.memory = memory;
        this.cpu = cpu;
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

        if (slaveTimoutInSeconds > 0) {
            this.slaveTimoutInSeconds = slaveTimoutInSeconds;
        } else {
            this.slaveTimoutInSeconds = DEFAULT_SLAVE_TIMEOUT;
        }
    }

    AmazonECSClient getAmazonECSClient() {
        return getEcsService().getAmazonECSClient();
    }

    public List<ECSTaskTemplate> getTemplates() {
        return templates;
    }

    public String getVpcId() {
        return vpcId;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public String getSecurityGroup() {
        return securityGroup;
    }

    public String getMemory() {
        return memory;
    }

    public String getCpu() {
        return cpu;
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

            List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();
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

            String uniq = Long.toHexString(System.nanoTime());
            slave = new ECSSlave(
                    ECSFargateCloud.this,
                    name + "-" + uniq,
                    template.getRemoteFSRoot(),
                    label == null ? null : label.toString(),
                    new JNLPLauncher()
            );
            slave.setClusterArn(getCluster());
            LOGGER.log(Level.INFO, "Created Slave: {0}", slave.getNodeName());

            runTask(getEcsService(), slave, getCluster(), jenkinsUrl, tunnel);
            waitForSlaveToBeOnline(slave, now, timeout);

            return slave;
        }
    }

    @Extension
    public static class DescriptorImpl extends ECSCloudDescriptor {

        public ListBoxModel doFillVpcIdItems(@QueryParameter String credentialsId, @QueryParameter String regionName){
            final ECSService ecsService = AWSClientsManager.getEcsService(credentialsId, regionName);
            try{
                final AmazonEC2Client client = ecsService.getAmazonEC2Client();
                DescribeVpcsResult result = client.describeVpcs();
                final ListBoxModel options = new ListBoxModel();
                for (Vpc vpc : result.getVpcs()) {
                    String vpcId = vpc.getVpcId();
                    String vpcName = vpc.getTags().stream()
                            .filter(tag -> tag.getKey().equals("Name"))
                            .findAny()
                            .map(tag -> " | " + tag.getValue())
                            .orElse("");
                    options.add(vpcId + vpcName, vpcId);
                }
                return options;
            } catch (AmazonClientException e) {
                // missing credentials will throw an "AmazonClientException: Unable to load AWS credentials from any provider in the chain"
                LOGGER.log(Level.INFO, "Exception searching VPCs for credentials=" + credentialsId + ", regionName=" + regionName + ":" + e);
                LOGGER.log(Level.FINE, "Exception searching VPCs for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            } catch (RuntimeException e) {
                LOGGER.log(Level.INFO, "Exception searching VPCs for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            }
        }

        public ListBoxModel doFillSubnetIdItems(@QueryParameter String credentialsId, @QueryParameter String regionName, @QueryParameter String vpcId){
            if(StringUtils.isEmpty(vpcId))
                return new ListBoxModel();

            final ECSService ecsService = AWSClientsManager.getEcsService(credentialsId, regionName);
            try{
                final AmazonEC2Client client = ecsService.getAmazonEC2Client();

                DescribeSubnetsRequest describeSubnetsRequest = new DescribeSubnetsRequest().withFilters(new Filter().withName("vpc-id").withValues(vpcId));
                DescribeSubnetsResult describeSubnetsResult = client.describeSubnets(describeSubnetsRequest);

                final ListBoxModel options = new ListBoxModel();
                for (Subnet subnet : describeSubnetsResult.getSubnets()) {
                    final String subnetId = subnet.getSubnetId();
                    final String subnetname = subnet.getTags().stream()
                            .filter(tag -> tag.getKey().equals("Name"))
                            .findAny()
                            .map(tag -> " | " + tag.getValue())
                            .orElse("");
                    options.add(subnetId + " | " + subnet.getVpcId() + subnetname, subnetId);
                }
                return options;
            } catch (AmazonClientException e) {
                // missing credentials will throw an "AmazonClientException: Unable to load AWS credentials from any provider in the chain"
                LOGGER.log(Level.INFO, "Exception searching subnets for credentials=" + credentialsId + ", regionName=" + regionName + ":" + e);
                LOGGER.log(Level.FINE, "Exception searching subnets for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            } catch (RuntimeException e) {
                LOGGER.log(Level.INFO, "Exception searching subnets for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            }
        }

        public ListBoxModel doFillSecurityGroupItems(@QueryParameter String credentialsId, @QueryParameter String regionName, @QueryParameter String vpcId){
            if(StringUtils.isEmpty(vpcId))
                return new ListBoxModel();

            final ECSService ecsService = AWSClientsManager.getEcsService(credentialsId, regionName);
            try{
                final AmazonEC2Client client = ecsService.getAmazonEC2Client();

                DescribeSecurityGroupsRequest describeSecurityGroupsRequest = new DescribeSecurityGroupsRequest()
                        .withFilters(new Filter().withName("vpc-id").withValues(vpcId));
                DescribeSecurityGroupsResult describeSecurityGroupsResult = client.describeSecurityGroups(describeSecurityGroupsRequest);

                final ListBoxModel options = new ListBoxModel();
                for (SecurityGroup securityGroup : describeSecurityGroupsResult.getSecurityGroups()) {
                    final String groupVpcId = securityGroup.getVpcId();
                    final String groupId = securityGroup.getGroupId();
                    final String groupName = securityGroup.getGroupName();
                    options.add(groupId + " | " + groupVpcId + " | " + groupName, groupId);
                }
                return options;
            } catch (AmazonClientException e) {
                // missing credentials will throw an "AmazonClientException: Unable to load AWS credentials from any provider in the chain"
                LOGGER.log(Level.INFO, "Exception searching security groups for credentials=" + credentialsId + ", regionName=" + regionName + ":" + e);
                LOGGER.log(Level.FINE, "Exception searching security groups for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            } catch (RuntimeException e) {
                LOGGER.log(Level.INFO, "Exception searching security groups for credentials=" + credentialsId + ", regionName=" + regionName, e);
                return new ListBoxModel();
            }
        }

        public ListBoxModel doFillMemoryItems(){
            ListBoxModel memoryItems = new ListBoxModel();

            memoryItems.add("0.5 GB", "512");
            IntStream.range(1, 31)
                    .forEach(value -> memoryItems.add(value + " GB", String.valueOf(value * 1024)));

            return memoryItems;
        }

        public ListBoxModel doFillCpuItems(){
            ListBoxModel cpuItems = new ListBoxModel();

            Stream.of(".25", ".5", "1", "2", "4").forEach(value -> {
                String cpuCredits = new BigDecimal(Double.parseDouble(value) * 1024)
                        .setScale(0, BigDecimal.ROUND_HALF_UP)
                        .toPlainString();
                cpuItems.add(value + " vCPU | " + cpuCredits, cpuCredits);
            });

            return cpuItems;
        }

        // https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-cpu-memory-error.html
        // valid cpu memory combinations
        public FormValidation doCheckCpu(@QueryParameter final String value, @QueryParameter String memory){
            boolean valid;
            switch (value){
                case "256":
                    valid = Arrays.asList("512","1024","2048").contains(memory);
                    break;
                case "512":
                    valid = getMemoryItems(1, 4).contains(memory);
                    break;
                case "1024":
                    valid = getMemoryItems(2, 8).contains(memory);
                    break;
                case "2048":
                    valid = getMemoryItems(4, 16).contains(memory);
                    break;
                case "4096":
                    valid = getMemoryItems(8, 30).contains(memory);
                    break;
                default:
                    return FormValidation.error("Invalid CPU value: " + value);
            }
            if(valid)
                return FormValidation.ok();
            else
                return FormValidation.error("Invalid CPU to Memory combination: " +
                        value + " CPU units and " + memory + "MB memory");
        }

        private List<String> getMemoryItems(int from, int to){
            return IntStream.range(from, to + 1).boxed()
                    .map(value -> String.valueOf(value * 1024))
                    .collect(Collectors.toList());
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
