package com.cloudbees.jenkins.plugins.amazonecs;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
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

class AWSClientsManager {

    private static final Logger LOGGER = Logger.getLogger(ECSCloud.class.getName());

    private static ConcurrentHashMap<String, ECSService> ecsServiceMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonIdentityManagement> iamClientsMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonECS> ecsClientsMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonAutoScaling> autoScalingClientsMap = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AmazonEC2> ec2ClientsMap = new ConcurrentHashMap<>();

    static ECSService getEcsService(final String credentialsId, final String regionName){
        return ecsServiceMap.computeIfAbsent(credentialsId+regionName, key -> new ECSService(credentialsId, regionName));
    }

    //
    //  Clients
    //

    static AmazonIdentityManagement getAmazonIAMClient(final String credentialsId, final String regionName) {
        return iamClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
            final AmazonIdentityManagement client;

            final ClientConfiguration clientConfiguration = getClientConfiguration();
            final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials == null) {
                // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
                // to use IAM Role define at the EC2 instance level ...
                //client = new AmazonIdentityManagementClient(clientConfiguration);
                client = AmazonIdentityManagementClientBuilder.standard()
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            } else {
                logAwsKey(credentials, "IAM");
                client = AmazonIdentityManagementClientBuilder.standard()
                        .withCredentials(credentials)
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            }
            LOGGER.log(Level.FINE, "Selected Region: {0}", regionName);
            return client;
        });
    }

    static AmazonECS getAmazonECSClient(final String credentialsId, final String regionName) {
        return ecsClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
            final AmazonECS client;
            final ClientConfiguration clientConfiguration = getClientConfiguration();
            final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials == null) {
                // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
                // to use IAM Role define at the EC2 instance level ...
                client = AmazonECSClientBuilder.standard()
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            } else {
                logAwsKey(credentials, "ECS");
                client = AmazonECSClientBuilder.standard()
                        .withCredentials(credentials)
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            }
            LOGGER.log(Level.FINE, "Selected Region: {0}", regionName);
            return client;
        });
    }

    static AmazonAutoScaling getAmazonAutoScalingClient(final String credentialsId, final String regionName) {
        return autoScalingClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
            final AmazonAutoScaling client;
            final ClientConfiguration clientConfiguration = getClientConfiguration();
            final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials == null) {
                // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
                // to use IAM Role define at the EC2 instance level ...
                client = AmazonAutoScalingClientBuilder.standard()
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            } else {
                logAwsKey(credentials, "AutoScaling");
                client = AmazonAutoScalingClientBuilder.standard()
                        .withCredentials(credentials)
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            }
            LOGGER.log(Level.FINE, "Selected Region: {0}", regionName);
            return client;
        });
    }

    static AmazonEC2 getAmazonEC2Client(final String credentialsId, final String regionName) {
        return ec2ClientsMap.computeIfAbsent(credentialsId+regionName, key -> {
            final AmazonEC2 client;
            final ClientConfiguration clientConfiguration = getClientConfiguration();
            final AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials == null) {
                // no credentials provided, rely on com.amazonaws.auth.DefaultAWSCredentialsProviderChain
                // to use IAM Role define at the EC2 instance level ...
                client = AmazonEC2ClientBuilder.standard()
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            } else {
                logAwsKey(credentials, "EC2");
                client = AmazonEC2ClientBuilder.standard()
                        .withCredentials(credentials)
                        .withClientConfiguration(clientConfiguration)
                        .withRegion(regionName)
                        .build();
            }
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

}
