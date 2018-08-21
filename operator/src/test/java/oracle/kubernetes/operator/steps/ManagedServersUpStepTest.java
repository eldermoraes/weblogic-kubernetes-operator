// Copyright 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import static oracle.kubernetes.LogMatcher.containsFine;
import static oracle.kubernetes.operator.WebLogicConstants.ADMIN_STATE;
import static oracle.kubernetes.operator.steps.ManagedServersUpStep.SERVERS_UP_MSG;
import static oracle.kubernetes.operator.steps.ManagedServersUpStepTest.TestStepFactory.getServerStartupInfo;
import static oracle.kubernetes.operator.steps.ManagedServersUpStepTest.TestStepFactory.getServers;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.meterware.simplestub.Memento;
import com.meterware.simplestub.StaticStubSupport;
import io.kubernetes.client.models.V1EnvVar;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Pod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import oracle.kubernetes.TestUtils;
import oracle.kubernetes.operator.StartupControlConstants;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo.ServerStartupInfo;
import oracle.kubernetes.operator.helpers.ServerKubernetesObjects;
import oracle.kubernetes.operator.helpers.ServerKubernetesObjectsManager;
import oracle.kubernetes.operator.wlsconfig.WlsClusterConfig;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.wlsconfig.WlsDynamicServersConfig;
import oracle.kubernetes.operator.wlsconfig.WlsMachineConfig;
import oracle.kubernetes.operator.wlsconfig.WlsServerConfig;
import oracle.kubernetes.operator.work.FiberTestSupport;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.operator.work.TerminalStep;
import oracle.kubernetes.weblogic.domain.v1.ClusterStartup;
import oracle.kubernetes.weblogic.domain.v1.Domain;
import oracle.kubernetes.weblogic.domain.v1.DomainSpec;
import oracle.kubernetes.weblogic.domain.v1.ServerStartup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the code to bring up managed servers. "Wls Servers" and "WLS Clusters" are those defined in
 * the admin server for the domain. There is also a kubernetes "Domain Spec," which specifies which
 * servers should be running.
 */
@SuppressWarnings({"ConstantConditions", "SameParameterValue"})
public class ManagedServersUpStepTest {

  private static final String DOMAIN = "domain";
  private static final String NS = "namespace";
  private static final String UID = "uid1";
  private final Domain domain = createDomain();

  private Map<String, WlsClusterConfig> wlsClusters = new HashMap<>();
  private Map<String, WlsServerConfig> wlsServers = new HashMap<>();
  private Map<String, WlsServerConfig> templates = new HashMap<>();
  private Map<String, WlsMachineConfig> machineConfigs = new HashMap<>();

  private Step nextStep = new TerminalStep();
  private FiberTestSupport testSupport = new FiberTestSupport();
  private List<Memento> mementos = new ArrayList<>();
  private DomainPresenceInfo domainPresenceInfo = createDomainPresenceInfo();
  private ManagedServersUpStep step = new ManagedServersUpStep(nextStep);
  private TestUtils.ConsoleHandlerMemento consoleHandlerMemento;

  private DomainPresenceInfo createDomainPresenceInfo() {
    return new DomainPresenceInfo(domain);
  }

  private Domain createDomain() {
    return new Domain().withMetadata(createMetaData()).withSpec(createDomainSpec());
  }

  private V1ObjectMeta createMetaData() {
    return new V1ObjectMeta().namespace(NS);
  }

  private DomainSpec createDomainSpec() {
    return new DomainSpec().withDomainUID(UID).withReplicas(1);
  }

  @Before
  public void setUp() throws NoSuchFieldException {
    mementos.add(consoleHandlerMemento = TestUtils.silenceOperatorLogger());
    mementos.add(TestStepFactory.install());
    testSupport.addDomainPresenceInfo(domainPresenceInfo);
  }

  @After
  public void tearDown() throws Exception {
    for (Memento memento : mementos) memento.revert();

    testSupport.throwOnCompletionFailure();
  }

  @Test
  public void whenEnabled_logCurrentServers() {
    List<LogRecord> messages = new ArrayList<>();
    consoleHandlerMemento.withLogLevel(Level.FINE).collectLogMessages(messages, SERVERS_UP_MSG);
    addRunningServer("admin");
    addRunningServer("ms1");
    addRunningServer("ms2");

    invokeStep();

    assertThat(messages, containsFine(SERVERS_UP_MSG));
  }

  private void addRunningServer(String serverName) {
    ServerKubernetesObjects sko =
        ServerKubernetesObjectsManager.getOrCreate(domainPresenceInfo, "", serverName);
    sko.getPod().set(new V1Pod());
  }

  @Test
  public void addExplicitlyStartedClusterMembersToExplicitlyRestartedServers() {
    addWlsCluster("cluster1", "ms1", "ms2");
    addWlsCluster("cluster2", "ms3", "ms4");
    addWlsCluster("cluster3", "ms5", "ms6");
    domainPresenceInfo.getExplicitRestartClusters().addAll(Arrays.asList("cluster1", "cluster3"));

    invokeStep();

    assertThat(domainPresenceInfo.getExplicitRestartClusters(), empty());
    assertThat(
        domainPresenceInfo.getExplicitRestartServers(),
        containsInAnyOrder("ms1", "ms2", "ms5", "ms6"));
  }

  @Test
  public void whenStartupControlUndefined_startManagedServers() {
    invokeStep();

    assertManagedServersToBeStarted();
  }

  @Test
  public void whenStartupControlAuto_startManagedServers() {
    setStartupControl(StartupControlConstants.AUTO_STARTUPCONTROL);

    invokeStep();

    assertManagedServersToBeStarted();
  }

  @Test
  public void whenStartupControlAll_startManagedServers() {
    setStartupControl(StartupControlConstants.ALL_STARTUPCONTROL);

    invokeStep();

    assertManagedServersToBeStarted();
  }

  @Test
  public void whenStartupControlSpecified_startManagedServers() {
    setStartupControl(StartupControlConstants.SPECIFIED_STARTUPCONTROL);

    invokeStep();

    assertManagedServersToBeStarted();
  }

  @Test
  public void whenStartupControlLowerCase_recognizeIt() {
    setStartupControl(StartupControlConstants.SPECIFIED_STARTUPCONTROL.toLowerCase());

    invokeStep();

    assertManagedServersToBeStarted();
  }

  private void assertManagedServersToBeStarted() {
    assertThat(TestStepFactory.next, instanceOf(ManagedServerUpIteratorStep.class));
  }

  @Test
  public void whenStartupControlAdmin_dontStartManagedServers() {
    setStartupControl(StartupControlConstants.ADMIN_STARTUPCONTROL);

    invokeStep();

    assertManagedServersWillNotBeStarted();
  }

  @Test
  public void whenStartupControlNone_dontStartManagedServers() {
    setStartupControl(StartupControlConstants.NONE_STARTUPCONTROL);

    invokeStep();

    assertManagedServersWillNotBeStarted();
  }

  @Test
  public void whenStartupControlNotRecognized_dontStartManagedServers() {
    setStartupControl("xyzzy");

    invokeStep();

    assertManagedServersWillNotBeStarted();
  }

  @Test
  public void whenWlsServerInDomainSpec_addToServerList() {
    configureServer("ms1");
    addWlsServer("ms1");

    invokeStep();

    assertThat(getServers(), contains("ms1"));
  }

  @Test
  public void whenWlsServerNotInDomainSpec_dontAddToServerList() {
    addWlsServer("ms1");

    invokeStep();

    assertThat(getServers(), empty());
  }

  @Test
  public void whenServerInDomainSpecButNotDefinedInWls_dontAddToServerList() {
    configureServer("ms1");

    invokeStep();

    assertThat(getServers(), empty());
  }

  @Test
  public void whenMultipleWlsServersInDomainSpec_addToServerList() {
    configureServers("ms1", "ms2", "ms3");
    addWlsServers("ms1", "ms2", "ms3");

    invokeStep();

    assertThat(getServers(), containsInAnyOrder("ms1", "ms2", "ms3"));
  }

  @Test
  public void whenMultipleWlsServersInDomainSpec_skipAdminServer() {
    defineAdminServer("ms2");
    configureServers("ms1", "ms2", "ms3");
    addWlsServers("ms1", "ms2", "ms3");

    invokeStep();

    assertThat(getServers(), containsInAnyOrder("ms1", "ms3"));
  }

  @Test
  public void whenWlsServersDuplicatedInDomainSpec_skipDuplicates() {
    defineAdminServer("admin");
    configureServers("ms1", "ms1", "ms2");
    addWlsServers("ms1", "ms2", "ms3");

    invokeStep();

    assertThat(getServers(), containsInAnyOrder("ms1", "ms2"));
  }

  @Test
  public void whenWlsServersInDomainSpec_addStartupInfo() {
    configureServer("ms1");
    configureServer("ms2");
    addWlsServers("ms1", "ms2");

    invokeStep();

    assertThat(getServerStartupInfo("ms1"), notNullValue());
    assertThat(getServerStartupInfo("ms2"), notNullValue());
  }

  @Test
  public void serverStartupInfo_containsWlsServerStartupAndConfig() {
    configureServer("ms1");
    addWlsServer("ms1");

    invokeStep();

    assertThat(getServerStartupInfo("ms1").serverConfig, sameInstance(getWlsServer("ms1")));
    assertThat(
        getServerStartupInfo("ms1").serverStartup, sameInstance(getServerConfiguration("ms1")));
  }

  @Test
  public void serverStartupInfo_containsEnvironmentVariable() {
    configureServer("ms1")
        .withEnvironmentVariable("item1", "value1")
        .withEnvironmentVariable("item2", "value2");
    addWlsServer("ms1");

    invokeStep();

    assertThat(
        getServerStartupInfo("ms1").envVars,
        containsInAnyOrder(envVar("item1", "value1"), envVar("item2", "value2")));
  }

  @Test
  public void whenDesiredStateIsAdmin_serverStartupCreatesJavaOptionsEnvironment() {
    configureServer("ms1").withDesiredState(ADMIN_STATE);
    addWlsServer("ms1");

    invokeStep();

    assertThat(
        getServerStartupInfo("ms1").envVars,
        hasItem(envVar("JAVA_OPTIONS", "-Dweblogic.management.startupMode=ADMIN")));
  }

  @Test
  public void whenDesiredStateIsAdmin_serverStartupAddsToJavaOptionsEnvironment() {
    configureServer("ms1")
        .withDesiredState(ADMIN_STATE)
        .withEnvironmentVariable("JAVA_OPTIONS", "value1");
    addWlsServer("ms1");

    invokeStep();

    assertThat(
        getServerStartupInfo("ms1").envVars,
        hasItem(envVar("JAVA_OPTIONS", "-Dweblogic.management.startupMode=ADMIN value1")));
  }

  @Test
  public void whenWlsServerNotInCluster_serverStartupInfoHasNoClusterConfig() {
    configureServer("ms1");
    addWlsServer("ms1");

    invokeStep();

    assertThat(getServerStartupInfo("ms1").clusterConfig, nullValue());
  }

  @Test
  public void whenWlsServerInCluster_serverStartupInfoHasMatchingClusterConfig() {
    configureServer("ms1");

    addWlsServer("ms1");
    addWlsCluster("cluster1", "ms1");
    addWlsCluster("cluster2");

    invokeStep();

    assertThat(getServerStartupInfo("ms1").clusterConfig, sameInstance(getWlsCluster("cluster1")));
  }

  @Test
  public void whenClusterStartupDefinedForServerNotRunning_addToServers() {
    configureServer("ms1");
    configureCluster("cluster1");
    addWlsCluster("cluster1", "ms1");

    invokeStep();

    assertThat(getServers(), hasItem("ms1"));
  }

  @Test
  public void whenClusterStartupDefinedForServerNotRunning_addServerStartup() {
    configureServer("ms1");
    configureCluster("cluster1");
    addWlsCluster("cluster1", "ms1");

    invokeStep();

    assertThat(
        getServerStartupInfo("ms1").serverConfig,
        sameInstance(getServerForWlsCluster("cluster1", "ms1")));
    assertThat(getServerStartupInfo("ms1").clusterConfig, sameInstance(getWlsCluster("cluster1")));
    assertThat(
        getServerStartupInfo("ms1").serverStartup, sameInstance(getServerConfiguration("ms1")));
  }

  @Test
  public void whenServerStartupNotDefined_useEnvForCluster() {
    configureCluster("cluster1").withEnvironmentVariable("item1", "value1");
    addWlsCluster("cluster1", "ms1");

    invokeStep();

    assertThat(getServerStartupInfo("ms1").envVars, contains(envVar("item1", "value1")));
  }

  @Test
  public void whenServerStartupDefined_overrideEnvFromCluster() {
    configureCluster("cluster1").withEnvironmentVariable("item1", "value1");
    configureServer("ms1").withEnvironmentVariable("item2", "value2");
    addWlsCluster("cluster1", "ms1");

    invokeStep();

    assertThat(getServerStartupInfo("ms1").envVars, contains(envVar("item2", "value2")));
  }

  @Test
  public void whenClusterStartupDefinedWithAdminState_addAdminEnv() {
    configureCluster("cluster1")
        .withDesiredState(ADMIN_STATE)
        .withEnvironmentVariable("item1", "value1");
    addWlsCluster("cluster1", "ms1");

    invokeStep();

    assertThat(
        getServerStartupInfo("ms1").envVars,
        hasItem(envVar("JAVA_OPTIONS", "-Dweblogic.management.startupMode=ADMIN")));
  }

  @Test
  public void withStartSpecifiedWhenWlsClusterNotInDomainSpec_dontAddServersToList() {
    setStartupControl(StartupControlConstants.SPECIFIED_STARTUPCONTROL);
    setDefaultReplicas(3);
    addWlsCluster("cluster1", "ms1", "ms2", "ms3", "ms4", "ms5");

    invokeStep();

    assertThat(getServers(), empty());
  }

  @Test
  public void withStartNoneWhenWlsClusterNotInDomainSpec_dontAddServersToList() {
    setStartupControl(StartupControlConstants.NONE_STARTUPCONTROL);
    setDefaultReplicas(3);
    addWlsCluster("cluster1", "ms1", "ms2", "ms3", "ms4", "ms5");

    invokeStep();

    assertThat(getServers(), empty());
  }

  @Test
  public void withStartAdminWhenWlsClusterNotInDomainSpec_dontAddServersToList() {
    setStartupControl(StartupControlConstants.ADMIN_STARTUPCONTROL);
    setDefaultReplicas(3);
    addWlsCluster("cluster1", "ms1", "ms2", "ms3", "ms4", "ms5");

    invokeStep();

    assertThat(getServers(), empty());
  }

  @Test
  public void withStartAutoWhenWlsClusterNotInDomainSpec_addServersToListUpToReplicaLimit() {
    setStartupControl(StartupControlConstants.AUTO_STARTUPCONTROL);
    setDefaultReplicas(3);
    addWlsCluster("cluster1", "ms1", "ms2", "ms3", "ms4", "ms5");

    invokeStep();

    assertThat(getServers(), containsInAnyOrder("ms1", "ms2", "ms3"));
  }

  @Test
  public void withStartAllWhenWlsClusterNotInDomainSpec_addClusteredServersToListUpWithoutLimit() {
    setStartupControl(StartupControlConstants.ALL_STARTUPCONTROL);
    setDefaultReplicas(3);
    addWlsCluster("cluster1", "ms1", "ms2", "ms3", "ms4", "ms5");

    invokeStep();

    assertThat(getServers(), containsInAnyOrder("ms1", "ms2", "ms3", "ms4", "ms5"));
    assertThat(getServerStartupInfo("ms4").clusterConfig, equalTo(getWlsCluster("cluster1")));
    assertThat(getServerStartupInfo("ms4").serverConfig, equalTo(getWlsServer("ms4")));
  }

  @Test
  public void whenWlsClusterNotInDomainSpec_recordServerAndClusterConfigs() {
    setDefaultReplicas(3);
    addWlsServers("ms1", "ms2", "ms3", "ms4", "ms5");
    addWlsCluster("cluster1", "ms1", "ms2", "ms3", "ms4", "ms5");

    invokeStep();

    assertThat(getServerStartupInfo("ms1").serverConfig, sameInstance(getWlsServer("ms1")));
    assertThat(getServerStartupInfo("ms1").clusterConfig, sameInstance(getWlsCluster("cluster1")));
    assertThat(getServerStartupInfo("ms1").envVars, empty());
    assertThat(getServerStartupInfo("ms1").serverStartup, nullValue());
  }

  @Test
  public void withStartupControlAll_addNonManagedServers() {
    setStartupControl(StartupControlConstants.ALL_STARTUPCONTROL);
    addWlsServer("ms1");

    invokeStep();

    assertThat(getServers(), hasItem("ms1"));
    assertThat(getServerStartupInfo("ms1").serverConfig, equalTo(getWlsServer("ms1")));
  }

  private WlsServerConfig addWlsServer(String serverName) {
    WlsServerConfig serverConfig = createServerConfig(serverName);
    wlsServers.put(serverName, serverConfig);
    return serverConfig;
  }

  private WlsServerConfig createServerConfig(String serverName) {
    return new ServerConfigBuilder(serverName).build();
  }

  private void setDefaultReplicas(int replicas) {
    domain.getSpec().setReplicas(replicas);
  }

  private void configureServers(String... serverNames) {
    for (String serverName : serverNames) {
      configureServer(serverName);
    }
  }

  private void addWlsServers(String... serverNames) {
    for (String serverName : serverNames) {
      addWlsServer(serverName);
    }
  }

  private void defineAdminServer(String adminServerName) {
    domain.getSpec().setAsName(adminServerName);
  }

  private WlsServerConfig getWlsServer(String serverName) {
    return wlsServers.get(serverName);
  }

  private ServerStartup configureServer(String serverName) {
    ServerStartup serverStartup = new ServerStartup().withServerName(serverName);
    domain.getSpec().addServerStartupItem(serverStartup);
    return serverStartup;
  }

  private V1EnvVar envVar(String name, String value) {
    return new V1EnvVar().name(name).value(value);
  }

  private void addWlsCluster(String clusterName, String... serverNames) {
    ClusterConfigBuilder builder = new ClusterConfigBuilder(clusterName);
    for (String serverName : serverNames) {
      builder.addServer(serverName);
    }
    wlsClusters.put(clusterName, builder.build());
  }

  private WlsClusterConfig getWlsCluster(String clusterName) {
    return wlsClusters.get(clusterName);
  }

  private ClusterStartup configureCluster(String clusterName) {
    ClusterStartup startup = new ClusterStartup().withClusterName(clusterName).withReplicas(1);
    domain.getSpec().addClusterStartupItem(startup);
    return startup;
  }

  private ServerStartup getServerConfiguration(String name) {
    for (ServerStartup startup : domain.getSpec().getServerStartup()) {
      if (startup.getServerName().equals(name)) return startup;
    }

    return null;
  }

  private WlsServerConfig getServerForWlsCluster(String clusterName, String serverName) {
    for (WlsServerConfig config : getWlsCluster(clusterName).getServerConfigs()) {
      if (config.getName().equals(serverName)) return config;
    }
    return null;
  }

  private void assertManagedServersWillNotBeStarted() {
    assertThat(TestStepFactory.next, sameInstance(nextStep));
  }

  private void setStartupControl(String startupcontrol) {
    domain.getSpec().setStartupControl(startupcontrol);
  }

  private void invokeStep() {
    domainPresenceInfo.setScan(createDomainConfig());
    testSupport.runSteps(step);
  }

  private WlsDomainConfig createDomainConfig() {
    return new WlsDomainConfig(DOMAIN, wlsClusters, wlsServers, templates, machineConfigs);
  }

  static class TestStepFactory implements ManagedServersUpStep.NextStepFactory {
    private static DomainPresenceInfo info;
    private static Collection<String> servers;
    private static Step next;
    private static TestStepFactory factory = new TestStepFactory();

    private static Memento install() throws NoSuchFieldException {
      factory = new TestStepFactory();
      return StaticStubSupport.install(ManagedServersUpStep.class, "NEXT_STEP_FACTORY", factory);
    }

    static Collection<String> getServers() {
      return servers;
    }

    static ServerStartupInfo getServerStartupInfo(String serverName) {
      for (ServerStartupInfo startupInfo : info.getServerStartupInfo()) {
        if (startupInfo.serverConfig.getName().equals(serverName)) return startupInfo;
      }

      return null;
    }

    @Override
    public Step createServerStep(DomainPresenceInfo info, Collection<String> servers, Step next) {
      TestStepFactory.info = info;
      TestStepFactory.servers = servers;
      TestStepFactory.next = next;
      return new TerminalStep();
    }
  }

  static class ServerConfigBuilder {
    private String name;

    ServerConfigBuilder(String name) {
      this.name = name;
    }

    WlsServerConfig build() {
      return new WlsServerConfig(name, null, null, null, false, null, null);
    }
  }

  class ClusterConfigBuilder {
    private String name;
    List<WlsServerConfig> serverConfigs = new ArrayList<>();

    ClusterConfigBuilder(String name) {
      this.name = name;
    }

    void addServer(String serverName) {
      WlsServerConfig serverConfig = wlsServers.get(serverName);
      serverConfigs.add(serverConfig != null ? serverConfig : addWlsServer(serverName));
    }

    WlsClusterConfig build() {
      return new WlsClusterConfig(name, getServers());
    }

    WlsDynamicServersConfig getServers() {
      return new WlsDynamicServersConfig(0, 0, "", false, "", null, serverConfigs);
    }
  }
}
