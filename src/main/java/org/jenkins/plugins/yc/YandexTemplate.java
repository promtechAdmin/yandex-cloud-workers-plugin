package org.jenkins.plugins.yc;

import com.google.protobuf.TextFormat;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Saveable;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import jenkins.slaves.iterators.api.NodeIterator;
import org.jenkins.plugins.yc.util.YCAgentConfig;
import org.jenkins.plugins.yc.util.YCAgentFactory;
import org.kohsuke.stapler.DataBoundConstructor;
import yandex.cloud.api.compute.v1.InstanceOuterClass;
import yandex.cloud.api.compute.v1.InstanceServiceOuterClass;
import yandex.cloud.api.operation.OperationOuterClass;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class YandexTemplate implements Describable<YandexTemplate> {

    private static final Logger LOGGER = Logger.getLogger(YandexTemplate.class.getName());

    protected transient AbstractCloud parent;

    public final String description;

    public final Node.Mode mode;
    public final String labels;
    public final String initScript;
    public final String remoteAdmin;

    public final String idleTerminationMinutes;

    public final boolean stopOnTerminate;

    private static final String userData = "#cloud-config%nusers:%n  - name: %s%n    sudo: ['ALL=(ALL) NOPASSWD:ALL']%n    ssh-authorized-keys:%n      - %s";

    private final List<YCTag> tags;

    private DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties;

    private transient Set<LabelAtom> labelSet;
    public int instanceCap;

    public enum ProvisionOptions { ALLOW_CREATE, FORCE_CREATE }

    @DataBoundConstructor
    public YandexTemplate(String description, Node.Mode mode, String labelString, String initScript, String remoteAdmin, String idleTerminationMinutes, boolean stopOnTerminate, List<YCTag> tags, String instanceCapStr) {
        this.labels = Util.fixNull(labelString);
        this.description = description;
        this.mode = mode;
        this.initScript = initScript;
        this.remoteAdmin = remoteAdmin;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.stopOnTerminate = stopOnTerminate;
        this.tags = tags;
        if (null == instanceCapStr || instanceCapStr.isEmpty()) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }
        readResolve();
    }

    @Override
    public Descriptor<YandexTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }


    protected Object readResolve() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        labelSet = Label.parse(labels);

        if (nodeProperties == null) {
            nodeProperties = new DescribableList<>(Saveable.NOOP);
        }

        if (instanceCap == 0) {
            instanceCap = Integer.MAX_VALUE;
        }

        return this;
    }

    public Node.Mode getMode() {
        return mode;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public String getInstanceCapStr() {
        if (instanceCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
    }

    public AbstractCloud getParent() {
        return parent;
    }

    public String getLabelString() {
        return labels;
    }

    public List<YCTag> getTags() {
        if (null == tags)
            return null;
        return Collections.unmodifiableList(tags);
    }

    public List<YCAbstractSlave> provision(int number, EnumSet<ProvisionOptions> provisionOptions) throws Exception {
        return provisionOnDemand(number, provisionOptions);
    }

    private List<YCAbstractSlave> provisionOnDemand(int number, EnumSet<ProvisionOptions> provisionOptions) throws Exception {
        YCPrivateKey ycPrivateKey = this.parent.resolvePrivateKey();
        if(ycPrivateKey == null){
            throw new Exception("Failed to get ssh");
        }
        List<InstanceOuterClass.Instance> orphans = findOrphansOrStopInstance(tplInstance(), number);
        if (orphans.isEmpty() && !provisionOptions.contains(ProvisionOptions.FORCE_CREATE) &&
                !provisionOptions.contains(ProvisionOptions.ALLOW_CREATE)) {
            logProvisionInfo("No existing instance found - but cannot create new instance");
            return null;
        }
        wakeUpInstance(orphans);
        if (orphans.size() == number) {
            return toSlaves(orphans.get(0));
        }
        int needCreateCount = number - orphans.size();
        InstanceServiceOuterClass.ListInstancesResponse listInstancesResponse = Api.getFilterInstanceResponse(this);
        if(needCreateCount > 0 && listInstancesResponse.getInstancesList().isEmpty()) {
            doCreateVM(this);
        }
        return toSlaves(tplInstance().get(0));
    }

    private void logProvisionInfo(String message) {
        LOGGER.log(Level.INFO, this + ". " + message);
    }

    private List<YCAbstractSlave> toSlaves(InstanceOuterClass.Instance instance) throws IOException {
        try {
            logProvisionInfo("Return instance: " + instance.toString());
            List<YCAbstractSlave> slaves = new ArrayList<>();
            slaves.add(newOnDemandSlave(instance));
            return slaves;
        } catch (Descriptor.FormException e) {
            throw new AssertionError(e); // we should have discovered all configuration issues upfront
        }
    }


    private YCAbstractSlave newOnDemandSlave(InstanceOuterClass.Instance instance) throws Descriptor.FormException, IOException {
        YCAgentConfig.OnDemand config = new YCAgentConfig.OnDemandBuilder()
                .withName(instance.getName())
                .withInstanceId(instance.getId())
                .withDescription(description)
                .withMode(mode)
                .withCloudName(parent.name)
                .withLabelString(labels)
                .withInitScript(initScript)
                .withRemoteAdmin(remoteAdmin)
                .withStopOnTerminate(stopOnTerminate)
                .withIdleTerminationMinutes(idleTerminationMinutes)
                .withLaunchTimeout(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .withNodeProperties(nodeProperties.toList())
                .build();
        return YCAgentFactory.getInstance().createOnDemandAgent(config);
    }

    private List<InstanceOuterClass.Instance> findOrphansOrStopInstance(List<InstanceOuterClass.Instance> tplAllInstance, int number) {
        List<InstanceOuterClass.Instance> orphans = new ArrayList<>();
        if (tplAllInstance == null) {
            return orphans;
        }
        int count = 0;
        for (InstanceOuterClass.Instance instance : tplAllInstance) {
            if (checkInstance(instance)) {
                // instance is not connected to jenkins
                orphans.add(instance);
                count++;
            }
            if (count == number) {
                return orphans;
            }
        }
        return orphans;
    }

    private boolean checkInstance(InstanceOuterClass.Instance instance) {
        for (YCAbstractSlave node : NodeIterator.nodes(YCAbstractSlave.class)) {
            if (node.getInstanceId().equals(instance.getId()) && !"STOPPED".equals(instance.getStatus().name())) {
                return false;
            }
        }
        return true;
    }

    private List<InstanceOuterClass.Instance> tplInstance() throws Exception {
        InstanceServiceOuterClass.ListInstancesResponse listInstancesResponse = Api.getFilterInstanceResponse(this);
        return listInstancesResponse.getInstancesList();
    }

    private void wakeUpInstance(List<InstanceOuterClass.Instance> orphans) {
        List<String> instances = new ArrayList<>();
        for (InstanceOuterClass.Instance sd : orphans) {
            if ("STOPPED".equals(sd.getStatus().name())) {
                instances.add(sd.getId());
            }
        }
        try {
            if (!instances.isEmpty()) {
                for(String instanceId : instances) {
                    Api.startInstance(this, instanceId);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage());
        }
    }

    public void doCreateVM(YandexTemplate template) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String remote = remoteAdmin.isEmpty() ? "root" : remoteAdmin;
        YCPrivateKey privateKey =  this.parent.resolvePrivateKey();
        if(privateKey == null){
            throw new Exception("Failed get ssh key");
        }
        InstanceServiceOuterClass.CreateInstanceRequest createInstanceRequest;
        InstanceServiceOuterClass.CreateInstanceRequest.Builder builder = InstanceServiceOuterClass.CreateInstanceRequest.newBuilder();
        TextFormat.merge(template.parent.initVMTemplate, builder);
        createInstanceRequest = builder
                .setName(parent.name)
                .setZoneId("ru-central1-b")
                .setFolderId(parent.folderId)
                .putMetadata("user-data", String.format(userData, remote, privateKey.getPublicFingerprint() + "= " + remote))
                .build();
        OperationOuterClass.Operation response = Api.createInstanceResponse(template, createInstanceRequest);
        if(!response.getError().getMessage().isEmpty()){
            throw new Exception("Error for create: " + response.getError().getMessage());
        }
    }


    @Extension
    public static final class DescriptorImpl extends Descriptor<YandexTemplate> {

        @Override
        public String getDisplayName() {
            return "";
        }
    }
}
