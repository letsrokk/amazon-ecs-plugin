package com.cloudbees.jenkins.plugins.amazonecs;

import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.ListClustersRequest;
import com.amazonaws.services.ecs.model.ListClustersResult;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ECSCloudDescriptor extends Descriptor<Cloud> {

    protected static final Logger LOGGER = Logger.getLogger(ECSCloud.class.getName());

    private static String CLOUD_NAME_PATTERN = "[a-z|A-Z|0-9|_|-]{1,127}";

    public ListBoxModel doFillCredentialsIdItems() {
        return AWSCredentialsHelper.doFillCredentialsIdItems(Jenkins.get());
    }

    public ListBoxModel doFillRegionNameItems() {
        final ListBoxModel options = new ListBoxModel();
        final List<Region> allRegions = new ArrayList<>(RegionUtils.getRegions());
        allRegions.sort(Comparator.comparing(Region::getName));
        for (Region region : allRegions) {
            options.add(region.getName());
        }
        return options;
    }

    public ListBoxModel doFillClusterItems(@QueryParameter String credentialsId, @QueryParameter String regionName) {
        final ECSService ecsService = AWSClientsManager.getEcsService(credentialsId, regionName);
        try {
            final AmazonECSClient client = ecsService.getAmazonECSClient();
            final List<String> allClusterArns = new ArrayList<>();
            String lastToken = null;
            do {
                final ListClustersResult result =
                        client.listClusters(new ListClustersRequest().withNextToken(lastToken));
                allClusterArns.addAll(result.getClusterArns());
                lastToken = result.getNextToken();
            } while (lastToken != null);
            Collections.sort(allClusterArns);
            final ListBoxModel options = new ListBoxModel();
            for (String arn : allClusterArns) {
                options.add(arn);
            }
            return options;
        } catch (AmazonClientException e) {
            // missing credentials will throw an "AmazonClientException: Unable to load AWS credentials from any provider in the chain"
            LOGGER.log(Level.INFO, "Exception searching clusters for credentials=" + credentialsId + ", regionName=" + regionName + ":" + e);
            LOGGER.log(Level.FINE, "Exception searching clusters for credentials=" + credentialsId + ", regionName=" + regionName, e);
            return new ListBoxModel();
        } catch (RuntimeException e) {
            LOGGER.log(Level.INFO, "Exception searching clusters for credentials=" + credentialsId + ", regionName=" + regionName, e);
            return new ListBoxModel();
        }
    }

    public FormValidation doCheckName(@QueryParameter final String value) {
        if (value.length() > 0 && value.length() <= 127 && value.matches(CLOUD_NAME_PATTERN)) {
            return FormValidation.ok();
        }
        return FormValidation.error("Up to 127 letters (uppercase and lowercase), numbers, hyphens, and underscores are allowed");
    }

}
