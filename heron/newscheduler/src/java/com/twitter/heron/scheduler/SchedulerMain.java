package com.twitter.heron.scheduler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.logging.Logger;

import javax.xml.bind.DatatypeConverter;

import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.proto.scheduler.Scheduler;

import com.twitter.heron.common.basics.FileUtils;
import com.twitter.heron.spi.common.Keys;
import com.twitter.heron.spi.common.Config;
import com.twitter.heron.spi.common.Context;
import com.twitter.heron.spi.common.PackingPlan;
import com.twitter.heron.spi.common.ClusterConfig;
import com.twitter.heron.spi.common.ClusterDefaults;

import com.twitter.heron.spi.packing.IPacking;
import com.twitter.heron.spi.scheduler.IScheduler;

import com.twitter.heron.spi.statemgr.IStateManager;
import com.twitter.heron.spi.statemgr.SchedulerStateManager;

import com.twitter.heron.spi.utils.Runtime;
import com.twitter.heron.spi.utils.TopologyUtils;
import com.twitter.heron.spi.utils.Shutdown;

import com.twitter.heron.scheduler.server.SchedulerServer;

/**
 * Main class of scheduler.
 */
public class SchedulerMain {
  private static final Logger LOG = Logger.getLogger(SchedulerMain.class.getName());

  /**
   * Load the topology config
   *
   * @param topologyJarFile, name of the user submitted topology jar/tar file
   * @param topologyDefnFile, name of the topology defintion file
   * @param topology, proto in memory version of topology definition 
   *
   * @return config, the topology config
   */
  protected static Config topologyConfigs(
      String topologyJarFile, String topologyDefnFile, TopologyAPI.Topology topology) {

    String pkgType = FileUtils.isOriginalPackageJar(
        FileUtils.getBaseName(topologyJarFile)) ? "jar" : "tar" ; 

    Config config = Config.newBuilder()
        .put(Keys.topologyId(), topology.getId())
        .put(Keys.topologyName(), topology.getName())
        .put(Keys.topologyDefinitionFile(), topologyDefnFile)
        .put(Keys.topologyJarFile(), topologyJarFile)
        .put(Keys.topologyPackageType(), pkgType)
        .build();

    return config;
  }

  /**
   * Load the defaults config
   *
   * @param heronHome, directory of heron home
   * @param configPath, directory containing the config
   *
   * return config, the defaults config
   */
  protected static Config defaultConfigs() {
    Config config = Config.newBuilder()
        .putAll(ClusterDefaults.getDefaults())
        .putAll(ClusterConfig.loadSandboxConfig())
        .build();
    return config;
  }

  /**
   * Load the config parameters from the command line
   *
   * @param cluster, name of the cluster
   * @param role, user role
   * @param environ, user provided environment/tag 
   *
   * @return config, the command line config
   */
  protected static Config commandLineConfigs(String cluster, String role, String environ) {
    Config config = Config.newBuilder()
        .put(Keys.cluster(), cluster)
        .put(Keys.role(), role)
        .put(Keys.environ(), environ)
        .build();
    return config;
  }

  public static void main(String[] args) throws
      ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {

    String cluster = args[0];
    String role = args[1];
    String environ = args[2];
    String topologyName = args[3];
    String topologyJarFile = args[4];
    int schedulerServerPort = Integer.parseInt(args[5]);

    // locate the topology definition file in the sandbox/working directory
    String topologyDefnFile = TopologyUtils.lookUpTopologyDefnFile(".", topologyName);

    // load the topology definition into topology proto
    TopologyAPI.Topology topology = TopologyUtils.getTopology(topologyDefnFile);

    // build the config by expanding all the variables
    Config config = Config.expand(
        Config.newBuilder()
            .putAll(defaultConfigs())
            .putAll(commandLineConfigs(cluster, role, environ))
            .putAll(topologyConfigs(topologyJarFile, topologyDefnFile, topology))
            .build());

    System.out.println("loaded scheduler config " + config);

    // run the scheduler
    runScheduler(config, schedulerServerPort, topology);
  }

  public static void runScheduler(
      Config config, int schedulerServerPort, TopologyAPI.Topology topology) throws
          ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {

    // create an instance of state manager
    String statemgrClass = Context.stateManagerClass(config);
    IStateManager statemgr = (IStateManager)Class.forName(statemgrClass).newInstance();

     // initialize the state manager
    statemgr.initialize(config);

    // create an instance of the packing class 
    String packingClass = Context.packingClass(config);
    IPacking packing = (IPacking)Class.forName(packingClass).newInstance();

    // build the runtime config
    Config runtime = Config.newBuilder()
        .put(Keys.topologyId(), topology.getId())
        .put(Keys.topologyName(), topology.getName())
        .put(Keys.topologyDefinition(), topology)
        .put(Keys.schedulerStateManager(), new SchedulerStateManager(statemgr))
        .put(Keys.numContainers(), 1 + TopologyUtils.getNumContainers(topology))
        .build();

    // get a packed plan and schedule it
    packing.initialize(config, runtime);
    PackingPlan packedPlan = packing.pack();

    // TO DO - investigate whether the heron executors can be started
    // in scheduler.schedule method - rather than in scheduler.initialize method
    Config ytruntime = Config.newBuilder()
        .putAll(runtime)
        .put(Keys.instanceDistribution(), TopologyUtils.packingToString(packedPlan))
        .put(Keys.schedulerShutdown(), new Shutdown())
        .build();

    // create an instance of scheduler 
    String schedulerClass = Context.schedulerClass(config);
    IScheduler scheduler = (IScheduler)Class.forName(schedulerClass).newInstance();

    // initialize the scheduler
    scheduler.initialize(config, ytruntime);

    // start the scheduler REST endpoint for receiving requests
    SchedulerServer server = runServer(runtime, scheduler, schedulerServerPort);

    // write the scheduler location to state manager.
    setSchedulerLocation(runtime, server);

    // schedule the packed plan
    scheduler.schedule(packedPlan);

    // wait until kill request or some interrupt occurs
    LOG.info("Waiting for termination... ");
    Runtime.schedulerShutdown(ytruntime).await();

    // stop the server and close the state manager
    LOG.info("Shutting down topology: " + topology.getName());
    server.stop();
    statemgr.close();
    System.exit(0);
  }

  /**
   * Run the http server for receiving scheduler requests
   *
   * @param runtime, the runtime configuration
   * @param scheduler, an instance of the scheduler
   * @param port, the port for scheduler to listen on
   *
   * @return an instance of the http server  
   */ 
  public static SchedulerServer runServer(
      Config runtime, IScheduler scheduler, int port) throws IOException {

    // create an instance of the server using scheduler class and port
    final SchedulerServer schedulerServer = new SchedulerServer(runtime, scheduler, port);

    // start the http server to manage runtime requests
    schedulerServer.start();

    return schedulerServer;
  }

  /**
   * Set the location of scheduler for other processes to discover 
   *
   * @param runtime, the runtime configuration
   * @param schedulerServe, the http server that scheduler listens for receives requests
   */
  public static void setSchedulerLocation(Config runtime, SchedulerServer schedulerServer) {

    // Set scheduler location to host:port by default. Overwrite scheduler location if behind DNS.
    Scheduler.SchedulerLocation location = Scheduler.SchedulerLocation.newBuilder()
        .setTopologyName(Runtime.topologyName(runtime))
        .setHttpEndpoint(String.format("%s:%d", schedulerServer.getHost(), schedulerServer.getPort()))
        .build();

    LOG.info("Setting SchedulerLocation: " + location);
    SchedulerStateManager schedulerStateManager = Runtime.schedulerStateManager(runtime);
    schedulerStateManager.setSchedulerLocation(location, Runtime.topologyName(runtime));
    // TODO - Should we wait on the future here
  }
}
