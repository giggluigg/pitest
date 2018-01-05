package org.pitest.controller;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.pitest.minion.commands.MinionConfig;
import org.pitest.mutationtest.TimeoutLengthStrategy;
import org.pitest.mutationtest.config.TestPluginArguments;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.execute.MutationEngineArguments;
import org.pitest.process.LaunchOptions;
import org.pitest.util.Unchecked;

public class Controller {

  private final String                classPath;
  private final File                  baseDir;
  private final TestPluginArguments   pitConfig;
  private final MutationEngineArguments  mutationEngine;
  private final boolean               verbose;
  private final LaunchOptions         config;
  private final int threads;
  private final TimeoutLengthStrategy timeout;
  
  
  public Controller(int threads, String classPath, File baseDir,
      TestPluginArguments pitConfig, MutationEngineArguments mutationEngine, boolean verbose,
      LaunchOptions config, TimeoutLengthStrategy timeout) {
    this.classPath = classPath;
    this.baseDir = baseDir;
    this.pitConfig = pitConfig;
    this.mutationEngine = mutationEngine;
    this.verbose = verbose;
    this.config = config;
    this.threads = threads;
    this.timeout = timeout;
  }

  public void process(Collection<MutationDetails> mutations, ResultListener listener) {

    try {
      // slight chance another process will steal the port before we use it
      // but we only do this once
      int myPort = findPort();
      System.out.println("Controller on " + myPort);
      System.out.println("Controller " + Thread.currentThread());
      
      MinionConfig minionConf = new MinionConfig(pitConfig.getTestPlugin(), 
          mutationEngine.getMutationEngine(), 
          pitConfig.getGroupConfig().getExcludedGroups().toArray(new String[0]), 
          pitConfig.getGroupConfig().getIncludedGroups().toArray(new String[0]),
          pitConfig.getExcludedRunners().toArray(new String[0]),
          mutationEngine.mutatorsArray(),
          mutationEngine.excludedMethodsArray()
          );
      
      
      JMXConnectorServer server = createJmxConnectorServer(myPort);
      server.start();
      
      
      WorkScheduler workScheduler =  new DeafultWorkScheduler(mutations, listener, timeout);
      MinionPool pool = new MinionPool(threads, new MinionFactory(myPort, classPath, baseDir, verbose, config), workScheduler);
     
      // FIXME this is global
      registerMXBean(pool, minionConf);

      
      pool.start();
      
      ScheduledExecutorService scheduler =
          Executors.newScheduledThreadPool(1);
      
      scheduler.scheduleAtFixedRate(runPerge(pool), 0, 4, TimeUnit.SECONDS);
      
     
      workScheduler.awaitCompletion(); 
      
      System.out.println("Stopping");
      
      scheduler.shutdownNow();
      server.stop();
      unregisterMXBean();
      
      System.out.println("done");
      
      
    } catch (Exception e) {
      throw Unchecked.translateCheckedException(e);
    }
  }
  


  private void unregisterMXBean() throws MBeanRegistrationException, InstanceNotFoundException {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    mbs.unregisterMBean(beanName());
    
  }

  private static Runnable runPerge(final MinionPool pool) {
    return new Runnable() {
      @Override
      public void run() {
        pool.pergeZombies();
      }
    };
  }

  private static JMXConnectorServer createJmxConnectorServer(int port)
      throws IOException {
    LocateRegistry.createRegistry(port);
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    JMXServiceURL url = new JMXServiceURL(
        "service:jmx:rmi://localhost/jndi/rmi://localhost:" + port + "/jmxrmi");
    return JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbs);
  }
  
  private static void registerMXBean(MinionPool pool, MinionConfig minionConf)
      throws MalformedObjectNameException, InstanceAlreadyExistsException,
      MBeanRegistrationException, NotCompliantMBeanException {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
  
    ObjectName mxbeanName = beanName();
    
    ControllerCommands mxbean = new ControllerCommands(pool, minionConf);

    mbs.registerMBean(mxbean, mxbeanName);
  }
  

  
  private static ObjectName beanName() {
   try {
    return new ObjectName(
          "org.pitest.minion:type=ControllerCommands");
  } catch (MalformedObjectNameException e) {
    throw Unchecked.translateCheckedException(e);
  }

  }
  
  
  private static int findPort() throws IOException {
    try (
        ServerSocket socket = new ServerSocket(0);
    ) {
      return socket.getLocalPort();

    }
  }
}