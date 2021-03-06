 /**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.controller.internal;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.ExecutionCommandWrapper;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariActionExecutionHelper;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.controller.ResourceProviderFactory;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.orm.PersistenceType;
import org.apache.ambari.server.orm.dao.ClusterDAO;
import org.apache.ambari.server.orm.dao.ClusterVersionDAO;
import org.apache.ambari.server.orm.dao.HostDAO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.dao.ResourceTypeDAO;
import org.apache.ambari.server.orm.dao.StackDAO;
import org.apache.ambari.server.orm.entities.ClusterEntity;
import org.apache.ambari.server.orm.entities.ClusterVersionEntity;
import org.apache.ambari.server.orm.entities.HostEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.ResourceEntity;
import org.apache.ambari.server.orm.entities.ResourceTypeEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.serveraction.upgrades.FinalizeUpgradeAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.ServiceOsSpecific;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.cluster.ClusterImpl;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.persist.PersistService;
import com.google.inject.util.Modules;


/**
 * ClusterStackVersionResourceProvider tests.
 */
public class ClusterStackVersionResourceProviderTest {

  public static final int MAX_TASKS_PER_STAGE = 2;
  private Injector injector;
  private AmbariMetaInfo ambariMetaInfo;
  private RepositoryVersionDAO repositoryVersionDAOMock;
  private ResourceTypeDAO resourceTypeDAO;
  private StackDAO stackDAO;
  private ClusterDAO clusterDAO;
  private ClusterVersionDAO clusterVersionDAO;
  private HostDAO hostDAO;
  private ConfigHelper configHelper;
  private Configuration configuration;
  private StageFactory stageFactory;
  private AmbariActionExecutionHelper actionExecutionHelper;

  private String operatingSystemsJson = "[\n" +
          "   {\n" +
          "      \"repositories\":[\n" +
          "         {\n" +
          "            \"Repositories/base_url\":\"http://s3.amazonaws.com/dev.hortonworks.com/HDP/centos5/2.x/updates/2.2.0.0\",\n" +
          "            \"Repositories/repo_name\":\"HDP-UTILS\",\n" +
          "            \"Repositories/repo_id\":\"HDP-UTILS-1.1.0.20\"\n" +
          "         },\n" +
          "         {\n" +
          "            \"Repositories/base_url\":\"http://s3.amazonaws.com/dev.hortonworks.com/HDP/centos5/2.x/updates/2.2.0.0\",\n" +
          "            \"Repositories/repo_name\":\"HDP\",\n" +
          "            \"Repositories/repo_id\":\"HDP-2.2\"\n" +
          "         }\n" +
          "      ],\n" +
          "      \"OperatingSystems/os_type\":\"redhat6\"\n" +
          "   }\n" +
          "]";


  @Before
  public void setup() throws Exception {
    // Create instances of mocks
    repositoryVersionDAOMock = createNiceMock(RepositoryVersionDAO.class);
    configHelper = createNiceMock(ConfigHelper.class);
    InMemoryDefaultTestModule inMemoryModule = new InMemoryDefaultTestModule();
    Properties properties = inMemoryModule.getProperties();
    properties.setProperty(Configuration.AGENT_PACKAGE_PARALLEL_COMMANDS_LIMIT_KEY,
            String.valueOf(MAX_TASKS_PER_STAGE));
    configuration = new Configuration(properties);
    stageFactory = createNiceMock(StageFactory.class);
    clusterVersionDAO = createNiceMock(ClusterVersionDAO.class);

    // Initialize injector
    injector = Guice.createInjector(Modules.override(inMemoryModule).with(new MockModule()));
    injector.getInstance(GuiceJpaInitializer.class);
    ambariMetaInfo = injector.getInstance(AmbariMetaInfo.class);
    resourceTypeDAO = injector.getInstance(ResourceTypeDAO.class);
    stackDAO = injector.getInstance(StackDAO.class);
    clusterDAO = injector.getInstance(ClusterDAO.class);
    hostDAO = injector.getInstance(HostDAO.class);
  }

  @After
  public void teardown() {
    injector.getInstance(PersistService.class).stop();
  }

  @Test
  public void testCreateResources() throws Exception {
    Resource.Type type = Resource.Type.ClusterStackVersion;

    AmbariManagementController managementController = createMock(AmbariManagementController.class);
    Clusters clusters = createNiceMock(Clusters.class);
    Cluster cluster = createNiceMock(Cluster.class);
    StackId stackId = new StackId("HDP", "2.0.1");

    Map<String, Host> hostsForCluster = new HashMap<String, Host>();
    int hostCount = 10;
    for (int i = 0; i < hostCount; i++) {
      String hostname = "host" + i;
      Host host = createNiceMock(hostname, Host.class);
      expect(host.getHostName()).andReturn(hostname).anyTimes();
      expect(host.getOsFamily()).andReturn("redhat6").anyTimes();
      replay(host);
      hostsForCluster.put(hostname, host);
    }

    final ServiceComponentHost schDatanode = createMock(ServiceComponentHost.class);
    expect(schDatanode.getServiceName()).andReturn("HDFS").anyTimes();
    expect(schDatanode.getServiceComponentName()).andReturn("DATANODE").anyTimes();
    final ServiceComponentHost schNamenode = createMock(ServiceComponentHost.class);
    expect(schNamenode.getServiceName()).andReturn("HDFS").anyTimes();
    expect(schNamenode.getServiceComponentName()).andReturn("NAMENODE").anyTimes();
    final ServiceComponentHost schAMS = createMock(ServiceComponentHost.class);
    expect(schAMS.getServiceName()).andReturn("AMBARI_METRICS").anyTimes();
    expect(schAMS.getServiceComponentName()).andReturn("METRICS_COLLECTOR").anyTimes();
    // First host contains versionable components
    final List<ServiceComponentHost> schsH1 = new ArrayList<ServiceComponentHost>(){{
      add(schDatanode);
      add(schNamenode);
      add(schAMS);
    }};
    // Second host does not contain versionable components
    final List<ServiceComponentHost> schsH2 = new ArrayList<ServiceComponentHost>(){{
      add(schAMS);
    }};

    RepositoryVersionEntity repoVersion = new RepositoryVersionEntity();
    repoVersion.setId(1l);
    repoVersion.setOperatingSystems(operatingSystemsJson);

    ServiceOsSpecific.Package hdfsPackage = new ServiceOsSpecific.Package();
    hdfsPackage.setName("hdfs");
    List<ServiceOsSpecific.Package> packages = Collections.singletonList(hdfsPackage);

    ActionManager actionManager = createNiceMock(ActionManager.class);

    RequestStatusResponse response = createNiceMock(RequestStatusResponse.class);
    ResourceProviderFactory resourceProviderFactory = createNiceMock(ResourceProviderFactory.class);
    ResourceProvider csvResourceProvider = createNiceMock(ClusterStackVersionResourceProvider.class);

    AbstractControllerResourceProvider.init(resourceProviderFactory);

    Map<String, Map<String, String>> hostConfigTags = new HashMap<String, Map<String, String>>();
    expect(configHelper.getEffectiveDesiredTags(anyObject(ClusterImpl.class), anyObject(String.class))).andReturn(hostConfigTags);

    expect(managementController.getClusters()).andReturn(clusters).anyTimes();
    expect(managementController.getAmbariMetaInfo()).andReturn(ambariMetaInfo).anyTimes();
    expect(managementController.getAuthName()).andReturn("admin").anyTimes();
    expect(managementController.getActionManager()).andReturn(actionManager).anyTimes();
    expect(managementController.getJdkResourceUrl()).andReturn("/JdkResourceUrl").anyTimes();
    expect(managementController.getPackagesForServiceHost(anyObject(ServiceInfo.class),
            (Map<String, String>) anyObject(List.class), anyObject(String.class))).
            andReturn(packages).times((hostCount - 1) * 2); // 1 host has no versionable components, other hosts have 2 services
//            // that's why we don't send commands to it

    expect(resourceProviderFactory.getHostResourceProvider(anyObject(Set.class), anyObject(Map.class),
            eq(managementController))).andReturn(csvResourceProvider).anyTimes();

    expect(clusters.getCluster(anyObject(String.class))).andReturn(cluster);
    expect(clusters.getHostsForCluster(anyObject(String.class))).andReturn(hostsForCluster);

    String clusterName = "Cluster100";
    //expect(cluster.getClusterName()).andReturn(clusterName).anyTimes();
    expect(cluster.getCurrentStackVersion()).andReturn(stackId);
    expect(cluster.getServiceComponentHosts(anyObject(String.class))).andAnswer(new IAnswer<List<ServiceComponentHost>>() {
      @Override
      public List<ServiceComponentHost> answer() throws Throwable {
        String hostname = (String) EasyMock.getCurrentArguments()[0];
        if (hostname.equals("host2")) {
          return schsH2;
        } else {
          return schsH1;
        }
      }
    }).anyTimes();

    ExecutionCommand executionCommand = createNiceMock(ExecutionCommand.class);
    ExecutionCommandWrapper executionCommandWrapper = createNiceMock(ExecutionCommandWrapper.class);

    expect(executionCommandWrapper.getExecutionCommand()).andReturn(executionCommand).anyTimes();

    Stage stage = createNiceMock(Stage.class);
    expect(stage.getExecutionCommandWrapper(anyObject(String.class), anyObject(String.class))).
            andReturn(executionCommandWrapper).anyTimes();

    // Check that we create proper stage count
    expect(stageFactory.createNew(anyLong(), anyObject(String.class),
            anyObject(String.class), anyLong(),
            anyObject(String.class), anyObject(String.class), anyObject(String.class),
            anyObject(String.class))).andReturn(stage).
            times((int) Math.ceil(hostCount / MAX_TASKS_PER_STAGE));

    expect(
            repositoryVersionDAOMock.findByStackAndVersion(
                    anyObject(StackId.class),
                    anyObject(String.class))).andReturn(repoVersion);

    expect(actionManager.getRequestTasks(anyLong())).andReturn(Collections.<HostRoleCommand>emptyList()).anyTimes();

    ClusterEntity clusterEntity = new ClusterEntity();
    clusterEntity.setClusterId(1l);
    clusterEntity.setClusterName(clusterName);
    ClusterVersionEntity cve = new ClusterVersionEntity(clusterEntity,
            repoVersion, RepositoryVersionState.INSTALL_FAILED, 0, "");
    expect(clusterVersionDAO.findByClusterAndStackAndVersion(anyObject(String.class),
            anyObject(StackId.class), anyObject(String.class))).andReturn(cve);

    // replay
    replay(managementController, response, clusters, resourceProviderFactory, csvResourceProvider,
            cluster, repositoryVersionDAOMock, configHelper, schDatanode, schNamenode, schAMS, actionManager,
            executionCommand, executionCommandWrapper,stage, stageFactory, clusterVersionDAO);

    ResourceProvider provider = AbstractControllerResourceProvider.getResourceProvider(
        type,
        PropertyHelper.getPropertyIds(type),
        PropertyHelper.getKeyPropertyIds(type),
        managementController);

    injector.injectMembers(provider);

    // add the property map to a set for the request.  add more maps for multiple creates
    Set<Map<String, Object>> propertySet = new LinkedHashSet<Map<String, Object>>();

    Map<String, Object> properties = new LinkedHashMap<String, Object>();

    // add properties to the request map
    properties.put(ClusterStackVersionResourceProvider.CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID, "Cluster100");
    properties.put(ClusterStackVersionResourceProvider.CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID, "2.2.0.1-885");
    properties.put(ClusterStackVersionResourceProvider.CLUSTER_STACK_VERSION_STACK_PROPERTY_ID, "HDP");
    properties.put(ClusterStackVersionResourceProvider.CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID, "2.1.1");

    propertySet.add(properties);





    // create the request
    Request request = PropertyHelper.getCreateRequest(propertySet, null);

    RequestStatus status = provider.createResources(request);

    // verify
    verify(managementController, response, clusters, stageFactory);

  }


  /**
   * Tests manual finalization scenario
   * @throws Exception
   */
  @Test
  public void testUpdateResources() throws Exception {
    Resource.Type type = Resource.Type.ClusterStackVersion;
    String clusterName = "Cluster100";

    AmbariManagementController managementController = createMock(AmbariManagementController.class);

    StackId stackId = new StackId("HDP", "2.0.1");
    StackEntity stackEntity = stackDAO.find(stackId.getStackName(), stackId.getStackVersion());
    Assert.assertNotNull(stackEntity);

    ResourceTypeEntity resourceTypeEntity = resourceTypeDAO.findById(ResourceTypeEntity.CLUSTER_RESOURCE_TYPE);
    if (resourceTypeEntity == null) {
      resourceTypeEntity = new ResourceTypeEntity();
      resourceTypeEntity.setId(ResourceTypeEntity.CLUSTER_RESOURCE_TYPE);
      resourceTypeEntity.setName(ResourceTypeEntity.CLUSTER_RESOURCE_TYPE_NAME);
      resourceTypeEntity = resourceTypeDAO.merge(resourceTypeEntity);
    }
    ResourceEntity resourceEntity = new ResourceEntity();
    resourceEntity.setResourceType(resourceTypeEntity);

    final Host host1 = createNiceMock("host1", Host.class);
    final Host host2 = createNiceMock("host2", Host.class);

    expect(host1.getHostName()).andReturn("host1").anyTimes();
    expect(host2.getHostName()).andReturn("host2").anyTimes();
    replay(host1, host2);

    ServiceComponentHost sch = createMock(ServiceComponentHost.class);
    List<ServiceComponentHost> schs = Collections.singletonList(sch);

    Cluster cluster = createNiceMock(Cluster.class);
    cluster.setClusterName(clusterName);

    ArrayList<Host> hosts = new ArrayList<Host>() {{
      add(host1);
      add(host2);
    }};

    Clusters clusters = createNiceMock(Clusters.class);
    expect(clusters.getCluster(anyObject(String.class))).andReturn(cluster);

    RepositoryVersionEntity repoVersion = new RepositoryVersionEntity();
    repoVersion.setOperatingSystems(operatingSystemsJson);
    StackEntity newDesiredStack = stackDAO.find("HDP", "2.0.1");
    repoVersion.setStack(newDesiredStack);

    final ServiceOsSpecific.Package hivePackage = new ServiceOsSpecific.Package();
    hivePackage.setName("hive");
    final ServiceOsSpecific.Package mysqlPackage = new ServiceOsSpecific.Package();
    mysqlPackage.setName("mysql");
    mysqlPackage.setSkipUpgrade(Boolean.TRUE);
    List<ServiceOsSpecific.Package> packages = new ArrayList<ServiceOsSpecific.Package>() {{
      add(hivePackage);
      add(mysqlPackage);
    }};

    ActionManager actionManager = createNiceMock(ActionManager.class);

    RequestStatusResponse response = createNiceMock(RequestStatusResponse.class);
    ResourceProviderFactory resourceProviderFactory = createNiceMock(ResourceProviderFactory.class);
    ResourceProvider csvResourceProvider = createNiceMock(ClusterStackVersionResourceProvider.class);

    CommandReport report = createNiceMock(CommandReport.class);
    FinalizeUpgradeAction finalizeUpgradeAction = createNiceMock(FinalizeUpgradeAction.class);

    AbstractControllerResourceProvider.init(resourceProviderFactory);

    Map<String, Map<String, String>> hostConfigTags = new HashMap<String, Map<String, String>>();
    expect(configHelper.getEffectiveDesiredTags(anyObject(ClusterImpl.class), anyObject(String.class))).andReturn(hostConfigTags);

    expect(managementController.getClusters()).andReturn(clusters).anyTimes();
    expect(managementController.getAmbariMetaInfo()).andReturn(ambariMetaInfo).anyTimes();
    expect(managementController.getAuthName()).andReturn("admin").anyTimes();
    expect(managementController.getActionManager()).andReturn(actionManager).anyTimes();
    expect(managementController.getJdkResourceUrl()).andReturn("/JdkResourceUrl").anyTimes();
    expect(managementController.getPackagesForServiceHost(anyObject(ServiceInfo.class),
            (Map<String, String>) anyObject(List.class), anyObject(String.class))).andReturn(packages).anyTimes();

    expect(resourceProviderFactory.getHostResourceProvider(anyObject(Set.class), anyObject(Map.class),
            eq(managementController))).andReturn(csvResourceProvider).anyTimes();

    expect(cluster.getCurrentStackVersion()).andReturn(stackId);
    expect(cluster.getServiceComponentHosts(anyObject(String.class))).andReturn(schs).anyTimes();

    Capture<StackId> capturedStackId = new Capture<StackId>();
    cluster.setDesiredStackVersion(capture(capturedStackId));
      expectLastCall().once();
    expect(cluster.getHosts()).andReturn(hosts).anyTimes();


    expect(sch.getServiceName()).andReturn("HIVE").anyTimes();

    expect(repositoryVersionDAOMock.findByDisplayName(anyObject(String.class))).andReturn(repoVersion);

    expect(actionManager.getRequestTasks(anyLong())).andReturn(Collections.<HostRoleCommand>emptyList()).anyTimes();

    expect(finalizeUpgradeAction.execute(null)).andReturn(report);

    expect(report.getStdOut()).andReturn("Dummy stdout");
    expect(report.getStdErr()).andReturn("Dummy stderr");
    expect(report.getStatus()).andReturn("COMPLETED");

    // replay
    replay(managementController, response, clusters, resourceProviderFactory, csvResourceProvider,
            cluster, repositoryVersionDAOMock, configHelper, sch, actionManager, finalizeUpgradeAction, report,
            stageFactory);

    ResourceProvider provider = AbstractControllerResourceProvider.getResourceProvider(
            type,
            PropertyHelper.getPropertyIds(type),
            PropertyHelper.getKeyPropertyIds(type),
            managementController);

    injector.injectMembers(provider);

    // Have to inject instance manually because injection via DI fails
    Field field = ClusterStackVersionResourceProvider.class.getDeclaredField("finalizeUpgradeAction");
    field.setAccessible(true);
    field.set(provider, finalizeUpgradeAction);

    // add the property map to a set for the request.  add more maps for multiple creates
    Map<String, Object> properties = new LinkedHashMap<String, Object>();

    // add properties to the request map
    properties.put(ClusterStackVersionResourceProvider.CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID, clusterName);
    properties.put(ClusterStackVersionResourceProvider.CLUSTER_STACK_VERSION_STATE_PROPERTY_ID, "CURRENT");
    properties.put(ClusterStackVersionResourceProvider.CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID, "HDP-2.2.2.0-2561");

    // create the request
    Request request = PropertyHelper.getUpdateRequest(properties, null);

    provider.updateResources(request, null);

    // verify
    verify(managementController, response);
    Assert.assertEquals(capturedStackId.getValue(),
            new StackId(newDesiredStack.getStackName(), newDesiredStack.getStackVersion()));
  }


  public class MockModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(RepositoryVersionDAO.class).toInstance(repositoryVersionDAOMock);
      bind(ConfigHelper.class).toInstance(configHelper);
      bind(Configuration.class).toInstance(configuration);
      bind(StageFactory.class).toInstance(stageFactory);
      bind(ClusterVersionDAO.class).toInstance(clusterVersionDAO);
    }
  }
}
