package com.cloudbees.jenkins.plugins.amazonecs;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AWSClientsManager {

    private static final Logger LOGGER = Logger.getLogger(ECSCloud.class.getName());

    private static ConcurrentHashMap<String, ECSService> ecsServiceMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonIdentityManagementClient> iamClientsMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonECSClient> ecsClientsMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonAutoScalingClient> autoScalingClientsMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonEC2Client> ec2ClientsMap = new ConcurrentHashMap<>();

    static ECSService getEcsService(final String credentialsId, final String regionName){
        return ecsServiceMap.computeIfAbsent(credentialsId+regionName, key -> new ECSService(credentialsId, regionName));
    }

    //
    //  Clients
    //

    static AmazonIdentityManagementClient getAmazonIAMClient(final String credentialsId, final String regionName) {
        return iamClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
            final AmazonIdentityManagementClient client;

            final ClientConfiguration clientConfiguration = getClientConfiguration();
            final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials == null) {
                // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
                // to use IAM Role define at the EC2 instance level ...
                client = new AmazonIdentityManagementClient(clientConfiguration);
            } else {
                logAwsKey(credentials, "IAM");
                client = new AmazonIdentityManagementClient(credentials, clientConfiguration);
            }
            client.setRegion(getRegion(regionName));
            LOGGER.log(Level.FINE, "Selected Region: {0}", regionName);
            return client;
        });
    }

    static AmazonECSClient getAmazonECSClient(final String credentialsId, final String regionName) {
        return ecsClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
            final AmazonECSClient client;
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
        });
    }

    static AmazonAutoScalingClient getAmazonAutoScalingClient(final String credentialsId, final String regionName) {
        return autoScalingClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
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
        });
    }

    static AmazonEC2Client getAmazonEC2Client(final String credentialsId, final String regionName) {
        return ec2ClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
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
        });
    }

    //
    //  Utils Methods
    //

    private static ClientConfiguration getClientConfiguration() {
        final ProxyConfiguration proxy = Jenkins.get().proxy;
        final ClientConfiguration clientConfiguration = new ClientConfiguration();
        if (proxy != null) {
            clientConfiguration.setProxyHost(proxy.name);
            clientConfiguration.setProxyPort(proxy.port);
            clientConfiguration.setProxyUsername(proxy.getUserName());
            clientConfiguration.setProxyPassword(proxy.getPassword());
        }
        return clientConfiguration;
    }

    @CheckForNull
    private static AmazonWebServicesCredentials getCredentials(@Nullable String credentialsId) {
        return AWSCredentialsHelper.getCredentials(credentialsId, Jenkins.get());
    }

    private static void logAwsKey(final AmazonWebServicesCredentials credentials, final String awsServiceName) {
        if (credentials != null && LOGGER.isLoggable(Level.FINE)) {
            final String awsAccessKeyId = credentials.getCredentials().getAWSAccessKeyId();
            final String obfuscatedAccessKeyId = StringUtils.left(awsAccessKeyId, 4)
                    + StringUtils.repeat("*", awsAccessKeyId.length() - 2 * 4) + StringUtils.right(awsAccessKeyId, 4);
            LOGGER.log(Level.FINE, "Connect to Amazon {0} with IAM Access Key {1}",
                    new Object[] {awsServiceName, obfuscatedAccessKeyId});
        }
    }

    private static Region getRegion(String regionName) {
        if (StringUtils.isNotEmpty(regionName)) {
            return RegionUtils.getRegion(regionName);
        } else {
            return Region.getRegion(Regions.US_EAST_1);
        }
    }

}
