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
package org.apache.ambari.server.state.stack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.state.stack.UpgradePack.ProcessingComponent;
import org.apache.ambari.server.state.stack.upgrade.ClusterGrouping;
import org.apache.ambari.server.state.stack.upgrade.ClusterGrouping.ExecuteStage;
import org.apache.ambari.server.state.stack.upgrade.ConfigureTask;
import org.apache.ambari.server.state.stack.upgrade.ConfigureTask.Transfer;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.Grouping;
import org.apache.ambari.server.state.stack.upgrade.RestartTask;
import org.apache.ambari.server.state.stack.upgrade.Task;
import org.apache.ambari.server.state.stack.upgrade.TransferOperation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.persist.PersistService;

/**
 * Tests for the upgrade pack
 */
public class UpgradePackTest {

  private Injector injector;
  private AmbariMetaInfo ambariMetaInfo;

  @Before
  public void before() throws Exception {
    injector = Guice.createInjector(new InMemoryDefaultTestModule());
    injector.getInstance(GuiceJpaInitializer.class);

    ambariMetaInfo = injector.getInstance(AmbariMetaInfo.class);
  }

  @After
  public void teardown() {
    injector.getInstance(PersistService.class).stop();
  }

  @Test
  public void testExistence() throws Exception {
    Map<String, UpgradePack> upgrades = ambariMetaInfo.getUpgradePacks("foo", "bar");
    assertTrue(upgrades.isEmpty());

    upgrades = ambariMetaInfo.getUpgradePacks("HDP", "2.1.1");
    assertTrue(upgrades.size() > 0);
    assertTrue(upgrades.containsKey("upgrade_test"));
  }

  @Test
  public void testUpgradeParsing() throws Exception {
    Map<String, UpgradePack> upgrades = ambariMetaInfo.getUpgradePacks("HDP", "2.1.1");
    assertTrue(upgrades.size() > 0);
    assertTrue(upgrades.containsKey("upgrade_test"));

    UpgradePack up = upgrades.get("upgrade_test");
    assertEquals("2.2.*", up.getTarget());

    Map<String, List<String>> expectedStages = new LinkedHashMap<String, List<String>>() {{
      put("ZOOKEEPER", Arrays.asList("ZOOKEEPER_SERVER"));
      put("HDFS", Arrays.asList("NAMENODE", "DATANODE"));
    }};

    // !!! test the tasks
    int i = 0;
    for (Entry<String, List<String>> entry : expectedStages.entrySet()) {
      assertTrue(up.getTasks().containsKey(entry.getKey()));
      assertEquals(i++, indexOf(up.getTasks(), entry.getKey()));

      // check that the number of components matches
      assertEquals(entry.getValue().size(), up.getTasks().get(entry.getKey()).size());

      // check component ordering
      int j = 0;
      for (String comp : entry.getValue()) {
        assertEquals(j++, indexOf(up.getTasks().get(entry.getKey()), comp));
      }
    }

    // !!! test specific tasks
    assertTrue(up.getTasks().containsKey("HDFS"));
    assertTrue(up.getTasks().get("HDFS").containsKey("NAMENODE"));

    ProcessingComponent pc = up.getTasks().get("HDFS").get("NAMENODE");
    assertNotNull(pc.preTasks);
    assertNotNull(pc.postTasks);
    assertNotNull(pc.tasks);
    assertNull(pc.preDowngradeTasks);
    assertNull(pc.postDowngradeTasks);
    assertEquals(1, pc.tasks.size());

    assertEquals(Task.Type.RESTART, pc.tasks.get(0).getType());
    assertEquals(RestartTask.class, pc.tasks.get(0).getClass());


    assertTrue(up.getTasks().containsKey("ZOOKEEPER"));
    assertTrue(up.getTasks().get("ZOOKEEPER").containsKey("ZOOKEEPER_SERVER"));

    pc = up.getTasks().get("HDFS").get("DATANODE");
    assertNotNull(pc.preDowngradeTasks);
    assertEquals(0, pc.preDowngradeTasks.size());
    assertNotNull(pc.postDowngradeTasks);
    assertEquals(1, pc.postDowngradeTasks.size());


    pc = up.getTasks().get("ZOOKEEPER").get("ZOOKEEPER_SERVER");
    assertNotNull(pc.preTasks);
    assertEquals(1, pc.preTasks.size());
    assertNotNull(pc.postTasks);
    assertEquals(1, pc.postTasks.size());
    assertNotNull(pc.tasks);
    assertEquals(1, pc.tasks.size());

    pc = up.getTasks().get("YARN").get("NODEMANAGER");
    assertNotNull(pc.preTasks);
    assertEquals(2, pc.preTasks.size());
    Task t = pc.preTasks.get(1);
    assertEquals(ConfigureTask.class, t.getClass());
    ConfigureTask ct = (ConfigureTask) t;
    assertEquals("core-site", ct.getConfigType());
    assertEquals(4, ct.getTransfers().size());

    /*
            <transfer operation="COPY" from-key="copy-key" to-key="copy-key-to" />
            <transfer operation="COPY" from-type="my-site" from-key="my-copy-key" to-key="my-copy-key-to" />
            <transfer operation="MOVE" from-key="move-key" to-key="move-key-to" />
            <transfer operation="DELETE" delete-key="delete-key">
              <keep-key>important-key</keep-key>
            </transfer>
    */
    Transfer t1 = ct.getTransfers().get(0);
    assertEquals(TransferOperation.COPY, t1.operation);
    assertEquals("copy-key", t1.fromKey);
    assertEquals("copy-key-to", t1.toKey);

    Transfer t2 = ct.getTransfers().get(1);
    assertEquals(TransferOperation.COPY, t2.operation);
    assertEquals("my-site", t2.fromType);
    assertEquals("my-copy-key", t2.fromKey);
    assertEquals("my-copy-key-to", t2.toKey);
    assertTrue(t2.keepKeys.isEmpty());

    Transfer t3 = ct.getTransfers().get(2);
    assertEquals(TransferOperation.MOVE, t3.operation);
    assertEquals("move-key", t3.fromKey);
    assertEquals("move-key-to", t3.toKey);

    Transfer t4 = ct.getTransfers().get(3);
    assertEquals(TransferOperation.DELETE, t4.operation);
    assertEquals("delete-key", t4.deleteKey);
    assertNull(t4.toKey);
    assertTrue(t4.preserveEdits);
    assertEquals(1, t4.keepKeys.size());
    assertEquals("important-key", t4.keepKeys.get(0));
  }

  @Test
  public void testGroupOrders() {
    Map<String, UpgradePack> upgrades = ambariMetaInfo.getUpgradePacks("HDP", "2.1.1");
    assertTrue(upgrades.size() > 0);
    assertTrue(upgrades.containsKey("upgrade_test_checks"));

    UpgradePack up = upgrades.get("upgrade_test_checks");

    List<String> expected_up = Arrays.asList(
        "PRE_CLUSTER",
        "ZOOKEEPER",
        "CORE_MASTER",
        "SERVICE_CHECK_1",
        "CORE_SLAVES",
        "SERVICE_CHECK_2",
        "POST_CLUSTER");

    List<String> expected_down = Arrays.asList(
        "PRE_CLUSTER",
        "CORE_SLAVES",
        "SERVICE_CHECK_2",
        "CORE_MASTER",
        "SERVICE_CHECK_1",
        "ZOOKEEPER",
        "POST_CLUSTER");

    int i = 0;
    List<Grouping> groups = up.getGroups(Direction.UPGRADE);
    for (Grouping g : groups) {
      assertEquals(expected_up.get(i), g.name);
      i++;
    }

    i = 0;
    groups = up.getGroups(Direction.DOWNGRADE);
    for (Grouping g : groups) {
      assertEquals(expected_down.get(i), g.name);
      i++;
    }
  }

  @Test
  public void testDirection() throws Exception {
    Map<String, UpgradePack> upgrades = ambariMetaInfo.getUpgradePacks("HDP", "2.1.1");
    assertTrue(upgrades.size() > 0);
    assertTrue(upgrades.containsKey("upgrade_direction"));

    UpgradePack up = upgrades.get("upgrade_direction");

    List<Grouping> groups = up.getGroups(Direction.UPGRADE);
    assertEquals(4, groups.size());
    Grouping group = groups.get(2);
    assertEquals(ClusterGrouping.class, group.getClass());
    ClusterGrouping cluster_group = (ClusterGrouping) group;
    assertEquals("Run on All", group.title);

    cluster_group = (ClusterGrouping) groups.get(3);
    List<ExecuteStage> stages = cluster_group.executionStages;
    assertEquals(3, stages.size());
    assertNotNull(stages.get(0).intendedDirection);
    assertEquals(Direction.DOWNGRADE, stages.get(0).intendedDirection);

    groups = up.getGroups(Direction.DOWNGRADE);
    assertEquals(3, groups.size());
    // there are two clustergroupings at the end
    group = groups.get(1);
    assertEquals(ClusterGrouping.class, group.getClass());
    assertEquals("Run on All", group.title);

    group = groups.get(2);
    assertEquals(ClusterGrouping.class, group.getClass());
    assertEquals("Finalize Upgrade", group.title);

  }


  private int indexOf(Map<String, ?> map, String keyToFind) {
    int result = -1;

    int i = 0;
    for (Entry<String, ?> entry : map.entrySet()) {
      if (entry.getKey().equals(keyToFind)) {
        return i;
      }
      i++;
    }

    return result;
  }


}
