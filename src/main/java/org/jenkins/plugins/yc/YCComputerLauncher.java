package org.jenkins.plugins.yc;

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.jvnet.localizer.Localizable;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;


public abstract class YCComputerLauncher extends ComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(YCComputerLauncher.class.getName());

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener listener) {
        try {
            YCComputer computer = (YCComputer) slaveComputer;
            launchScript(computer, listener);
        } catch (Exception e) {
            e.printStackTrace(listener.error(e.getMessage()));
            if (slaveComputer.getNode() != null) {
                LOGGER.log(Level.FINE, String.format("Terminating the yc agent %s due a problem launching or connecting to it", slaveComputer.getName()), e);
                YCAbstractSlave ycAbstractSlave = (YCAbstractSlave) slaveComputer.getNode();
                if (ycAbstractSlave != null) {
                    Localizable cleanUpReason = Messages._Agent_Failed_To_Connect();
                    slaveComputer.setAcceptingTasks(false);
                    YCComputer computer = (YCComputer) ycAbstractSlave.toComputer();
                    if(computer != null) {
                        computer.setTemporarilyOffline(true, OfflineCause.create(cleanUpReason));
                    }
                    ycAbstractSlave.terminate();
                }
            }
        }
    }


    protected abstract boolean launchScript(YCComputer computer, TaskListener listener)
            throws IOException, InterruptedException;

}
