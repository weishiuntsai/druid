/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.overlord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.druid.indexer.TaskLocation;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.IndexingServiceCondition;
import org.apache.druid.indexing.common.TaskLockType;
import org.apache.druid.indexing.common.TestIndexTask;
import org.apache.druid.indexing.common.TestTasks;
import org.apache.druid.indexing.common.TestUtils;
import org.apache.druid.indexing.common.actions.SegmentTransactionalAppendAction;
import org.apache.druid.indexing.common.actions.SegmentTransactionalInsertAction;
import org.apache.druid.indexing.common.actions.SegmentTransactionalReplaceAction;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.overlord.config.RemoteTaskRunnerConfig;
import org.apache.druid.indexing.overlord.setup.DefaultWorkerBehaviorConfig;
import org.apache.druid.indexing.overlord.setup.EqualDistributionWorkerSelectStrategy;
import org.apache.druid.indexing.worker.Worker;
import org.apache.druid.indexing.worker.config.WorkerConfig;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.druid.testing.DeadlockDetectingTimeout;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class RemoteTaskRunnerTest
{
  private static final Logger LOG = new Logger(RemoteTaskRunnerTest.class);
  private static final Joiner JOINER = RemoteTaskRunnerTestUtils.JOINER;
  private static final String WORKER_HOST = "worker";
  private static final String ANNOUCEMENTS_PATH = JOINER.join(
      RemoteTaskRunnerTestUtils.ANNOUNCEMENTS_PATH,
      WORKER_HOST
  );
  private static final String STATUS_PATH = JOINER.join(RemoteTaskRunnerTestUtils.STATUS_PATH, WORKER_HOST);

  // higher timeout to reduce flakiness on CI pipeline
  private static final Period TIMEOUT_PERIOD = Period.millis(30000);

  private RemoteTaskRunner remoteTaskRunner;
  private HttpClient httpClient;
  private RemoteTaskRunnerTestUtils rtrTestUtils = new RemoteTaskRunnerTestUtils();
  private ObjectMapper jsonMapper;
  private CuratorFramework cf;

  private Task task;
  private Worker worker;

  @Rule
  public TestRule watcher = new TestWatcher()
  {
    @Override
    protected void starting(Description description)
    {
      LOG.info("Starting test: " + description.getMethodName());
    }

    @Override
    protected void finished(Description description)
    {
      LOG.info("Finishing test: " + description.getMethodName());
    }
  };

  @Rule
  public final TestRule timeout = new DeadlockDetectingTimeout(60, TimeUnit.SECONDS);

  @Before
  public void setUp() throws Exception
  {
    rtrTestUtils.setUp();
    jsonMapper = rtrTestUtils.getObjectMapper();
    cf = rtrTestUtils.getCuratorFramework();

    task = TestTasks.unending("task id with spaces");
    EmittingLogger.registerEmitter(new NoopServiceEmitter());
  }

  @After
  public void tearDown() throws Exception
  {
    if (remoteTaskRunner != null) {
      remoteTaskRunner.stop();
    }
    rtrTestUtils.tearDown();
  }

  @Test
  public void testRun() throws Exception
  {
    doSetup();

    Assert.assertEquals(3, remoteTaskRunner.getTotalTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertEquals(3, remoteTaskRunner.getIdleTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertEquals(0, remoteTaskRunner.getUsedTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertEquals(3, remoteTaskRunner.getTotalCapacity());
    Assert.assertEquals(-1, remoteTaskRunner.getMaximumCapacityWithAutoscale());
    Assert.assertEquals(0, remoteTaskRunner.getUsedCapacity());


    ListenableFuture<TaskStatus> result = remoteTaskRunner.run(task);

    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);
    Assert.assertTrue(workerRunningTask(task.getId()));

    mockWorkerCompleteSuccessfulTask(task);
    Assert.assertTrue(workerCompletedTask(result));
    Assert.assertEquals(task.getId(), result.get().getId());
    Assert.assertEquals(TaskState.SUCCESS, result.get().getStatusCode());

    cf.delete().guaranteed().forPath(JOINER.join(STATUS_PATH, task.getId()));

    Assert.assertEquals(3, remoteTaskRunner.getTotalTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertEquals(3, remoteTaskRunner.getIdleTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertEquals(0, remoteTaskRunner.getUsedTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertEquals(3, remoteTaskRunner.getTotalCapacity());
    Assert.assertEquals(0, remoteTaskRunner.getUsedCapacity());
  }

  @Test
  public void testRunTaskThatAlreadyPending() throws Exception
  {
    doSetup();
    remoteTaskRunner.addPendingTask(task);
    remoteTaskRunner.runPendingTasks();
    Assert.assertFalse(workerRunningTask(task.getId()));

    ListenableFuture<TaskStatus> result = remoteTaskRunner.run(task);

    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);
    Assert.assertTrue(workerRunningTask(task.getId()));
    mockWorkerCompleteSuccessfulTask(task);
    Assert.assertTrue(workerCompletedTask(result));

    Assert.assertEquals(task.getId(), result.get().getId());
    Assert.assertEquals(TaskState.SUCCESS, result.get().getStatusCode());
  }

  @Test
  public void testStartWithNoWorker()
  {
    makeRemoteTaskRunner(new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD));
  }

  @Test
  public void testRunExistingTaskThatHasntStartedRunning() throws Exception
  {
    doSetup();

    remoteTaskRunner.run(task);
    Assert.assertTrue(taskAnnounced(task.getId()));

    ListenableFuture<TaskStatus> result = remoteTaskRunner.run(task);

    Assert.assertFalse(result.isDone());
    mockWorkerRunningTask(task);
    Assert.assertTrue(workerRunningTask(task.getId()));
    mockWorkerCompleteSuccessfulTask(task);
    Assert.assertTrue(workerCompletedTask(result));

    Assert.assertEquals(task.getId(), result.get().getId());
    Assert.assertEquals(TaskState.SUCCESS, result.get().getStatusCode());
  }

  @Test
  public void testRunExistingTaskThatHasStartedRunning() throws Exception
  {
    doSetup();

    remoteTaskRunner.run(task);
    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);
    Assert.assertTrue(workerRunningTask(task.getId()));

    ListenableFuture<TaskStatus> result = remoteTaskRunner.run(task);

    Assert.assertFalse(result.isDone());

    mockWorkerCompleteSuccessfulTask(task);
    Assert.assertTrue(workerCompletedTask(result));

    Assert.assertEquals(task.getId(), result.get().getId());
    Assert.assertEquals(TaskState.SUCCESS, result.get().getStatusCode());
  }

  @Test
  public void testRunTooMuchZKData() throws Exception
  {
    ServiceEmitter emitter = EasyMock.createMock(ServiceEmitter.class);
    EmittingLogger.registerEmitter(emitter);
    EasyMock.replay(emitter);

    doSetup();

    remoteTaskRunner.run(TestTasks.unending(new String(new char[5000])));

    EasyMock.verify(emitter);
  }

  @Test
  public void testRunSameAvailabilityGroup() throws Exception
  {
    doSetup();

    TestIndexTask task1 = new TestIndexTask(
        "rt1",
        new TaskResource("rt1", 1),
        "foo",
        TaskStatus.running("rt1"),
        jsonMapper
    );
    remoteTaskRunner.run(task1);
    Assert.assertTrue(taskAnnounced(task1.getId()));
    mockWorkerRunningTask(task1);

    TestIndexTask task2 = new TestIndexTask(
        "rt2",
        new TaskResource("rt1", 1),
        "foo",
        TaskStatus.running("rt2"),
        jsonMapper
    );
    remoteTaskRunner.run(task2);

    TestIndexTask task3 = new TestIndexTask(
        "rt3",
        new TaskResource("rt2", 1),
        "foo",
        TaskStatus.running("rt3"),
        jsonMapper
    );
    remoteTaskRunner.run(task3);

    Assert.assertTrue(
        TestUtils.conditionValid(
            new IndexingServiceCondition()
            {
              @Override
              public boolean isValid()
              {
                return remoteTaskRunner.getRunningTasks().size() == 2;
              }
            }
        )
    );

    Assert.assertTrue(
        TestUtils.conditionValid(
            new IndexingServiceCondition()
            {
              @Override
              public boolean isValid()
              {
                return remoteTaskRunner.getPendingTasks().size() == 1;
              }
            }
        )
    );

    Assert.assertTrue(remoteTaskRunner.getPendingTasks().iterator().next().getTaskId().equals("rt2"));
  }

  @Test
  public void testRunWithCapacity() throws Exception
  {
    doSetup();

    TestIndexTask task1 = new TestIndexTask(
        "rt1",
        new TaskResource("rt1", 1),
        "foo",
        TaskStatus.running("rt1"),
        jsonMapper
    );
    remoteTaskRunner.run(task1);
    Assert.assertTrue(taskAnnounced(task1.getId()));
    mockWorkerRunningTask(task1);

    TestIndexTask task2 = new TestIndexTask(
        "rt2",
        new TaskResource("rt2", 3),
        "foo",
        TaskStatus.running("rt2"),
        jsonMapper
    );
    remoteTaskRunner.run(task2);

    TestIndexTask task3 = new TestIndexTask(
        "rt3",
        new TaskResource("rt3", 2),
        "foo",
        TaskStatus.running("rt3"),
        jsonMapper
    );
    remoteTaskRunner.run(task3);
    Assert.assertTrue(taskAnnounced(task3.getId()));
    mockWorkerRunningTask(task3);

    Assert.assertTrue(
        TestUtils.conditionValid(
            new IndexingServiceCondition()
            {
              @Override
              public boolean isValid()
              {
                return remoteTaskRunner.getRunningTasks().size() == 2;
              }
            }
        )
    );

    Assert.assertTrue(
        TestUtils.conditionValid(
            new IndexingServiceCondition()
            {
              @Override
              public boolean isValid()
              {
                return remoteTaskRunner.getPendingTasks().size() == 1;
              }
            }
        )
    );

    Assert.assertTrue(remoteTaskRunner.getPendingTasks().iterator().next().getTaskId().equals("rt2"));
  }

  @Test
  public void testStatusRemoved() throws Exception
  {
    doSetup();

    ListenableFuture<TaskStatus> future = remoteTaskRunner.run(task);
    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);

    Assert.assertTrue(workerRunningTask(task.getId()));

    Assert.assertTrue(remoteTaskRunner.getRunningTasks().iterator().next().getTaskId().equals(task.getId()));

    cf.delete().forPath(JOINER.join(STATUS_PATH, task.getId()));

    TaskStatus status = future.get();

    Assert.assertEquals(status.getStatusCode(), TaskState.FAILED);
    Assert.assertNotNull(status.getErrorMsg());
    Assert.assertTrue(status.getErrorMsg().contains("The worker that this task was assigned disappeared"));
  }

  @Test
  public void testBootstrap() throws Exception
  {
    makeWorker();

    RemoteTaskRunnerConfig rtrConfig = new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD);
    rtrConfig.setMaxPercentageBlacklistWorkers(100);

    makeRemoteTaskRunner(rtrConfig);

    TestIndexTask task1 = new TestIndexTask(
        "first",
        new TaskResource("first", 1),
        "foo",
        TaskStatus.running("first"),
        jsonMapper
    );
    remoteTaskRunner.run(task1);
    Assert.assertTrue(taskAnnounced(task1.getId()));
    mockWorkerRunningTask(task1);

    TestIndexTask task = new TestIndexTask(
        "second",
        new TaskResource("task", 2),
        "foo",
        TaskStatus.running("task"),
        jsonMapper
    );
    remoteTaskRunner.run(task);

    TestIndexTask task2 = new TestIndexTask(
        "second",
        new TaskResource("second", 2),
        "foo",
        TaskStatus.running("second"),
        jsonMapper
    );
    remoteTaskRunner.run(task2);
    Assert.assertTrue(taskAnnounced(task2.getId()));
    mockWorkerRunningTask(task2);

    final Set<String> runningTasks = Sets.newHashSet(
        Iterables.transform(
            remoteTaskRunner.getRunningTasks(),
            new Function<>()
            {
              @Override
              public String apply(RemoteTaskRunnerWorkItem input)
              {
                return input.getTaskId();
              }
            }
        )
    );
    Assert.assertEquals("runningTasks", ImmutableSet.of("first", "second"), runningTasks);
  }

  @Test
  public void testRunWithTaskComplete() throws Exception
  {
    doSetup();
    TestIndexTask task1 = new TestIndexTask(
        "testTask",
        new TaskResource("testTask", 2),
        "foo",
        TaskStatus.success("testTask"),
        jsonMapper
    );
    remoteTaskRunner.run(task1);
    Assert.assertTrue(taskAnnounced(task1.getId()));
    mockWorkerRunningTask(task1);
    mockWorkerCompleteSuccessfulTask(task1);

    Assert.assertEquals(TaskState.SUCCESS, remoteTaskRunner.run(task1).get().getStatusCode());
  }

  @Test
  public void testWorkerRemoved() throws Exception
  {
    doSetup();
    Assert.assertEquals(3, remoteTaskRunner.getTotalTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertEquals(3, remoteTaskRunner.getIdleTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());

    Future<TaskStatus> future = remoteTaskRunner.run(task);

    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);

    Assert.assertTrue(workerRunningTask(task.getId()));

    cf.delete().forPath(ANNOUCEMENTS_PATH);

    TaskStatus status = future.get();

    Assert.assertEquals(TaskState.FAILED, status.getStatusCode());
    Assert.assertNotNull(status.getErrorMsg());
    Assert.assertTrue(status.getErrorMsg().contains("Canceled for worker cleanup"));
    RemoteTaskRunnerConfig config = remoteTaskRunner.getRemoteTaskRunnerConfig();
    Assert.assertTrue(
        TestUtils.conditionValid(
            new IndexingServiceCondition()
            {
              @Override
              public boolean isValid()
              {
                return remoteTaskRunner.getRemovedWorkerCleanups().isEmpty();
              }
            },
            // cleanup task is independently scheduled by event listener. we need to wait some more time.
            config.getTaskCleanupTimeout().toStandardDuration().getMillis() * 2
        )
    );
    Assert.assertNull(cf.checkExists().forPath(STATUS_PATH));

    Assert.assertFalse(remoteTaskRunner.getTotalTaskSlotCount().containsKey(WorkerConfig.DEFAULT_CATEGORY));
    Assert.assertFalse(remoteTaskRunner.getIdleTaskSlotCount().containsKey(WorkerConfig.DEFAULT_CATEGORY));
  }

  @Test
  public void testWorkerDisabled() throws Exception
  {
    doSetup();
    final ListenableFuture<TaskStatus> result = remoteTaskRunner.run(task);

    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);
    Assert.assertTrue(workerRunningTask(task.getId()));

    // Disable while task running
    disableWorker();

    // Continue test
    mockWorkerCompleteSuccessfulTask(task);
    Assert.assertTrue(workerCompletedTask(result));
    Assert.assertEquals(task.getId(), result.get().getId());
    Assert.assertEquals(TaskState.SUCCESS, result.get().getStatusCode());

    // Confirm RTR thinks the worker is disabled.
    Assert.assertEquals("", Iterables.getOnlyElement(remoteTaskRunner.getWorkers()).getWorker().getVersion());
  }

  @Test
  public void testRestartRemoteTaskRunner() throws Exception
  {
    doSetup();
    remoteTaskRunner.run(task);

    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);
    Assert.assertTrue(workerRunningTask(task.getId()));

    remoteTaskRunner.stop();
    makeRemoteTaskRunner(new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD));
    final RemoteTaskRunnerWorkItem newWorkItem = remoteTaskRunner
        .getKnownTasks()
        .stream()
        .filter(workItem -> workItem.getTaskId().equals(task.getId()))
        .findFirst()
        .orElse(null);
    final ListenableFuture<TaskStatus> result = newWorkItem.getResult();

    mockWorkerCompleteSuccessfulTask(task);
    Assert.assertTrue(workerCompletedTask(result));

    Assert.assertEquals(task.getId(), result.get().getId());
    Assert.assertEquals(TaskState.SUCCESS, result.get().getStatusCode());
  }

  @Test
  public void testRunPendingTaskFailToAssignTask() throws Exception
  {
    doSetup();
    Thread.sleep(100);
    RemoteTaskRunnerWorkItem originalItem = remoteTaskRunner.addPendingTask(task);
    // modify taskId to make task assignment failed
    RemoteTaskRunnerWorkItem wankyItem = Mockito.mock(RemoteTaskRunnerWorkItem.class);
    Mockito.when(wankyItem.getTaskId()).thenReturn(originalItem.getTaskId()).thenReturn("wrongId");
    remoteTaskRunner.runPendingTask(wankyItem);
    TaskStatus taskStatus = originalItem.getResult().get(0, TimeUnit.MILLISECONDS);
    Assert.assertEquals(TaskState.FAILED, taskStatus.getStatusCode());
    Assert.assertEquals(
        "Failed to assign this task. See overlord logs for more details.",
        taskStatus.getErrorMsg()
    );
  }

  @Test
  public void testRunPendingTaskTimeoutToAssign() throws Exception
  {
    makeWorker();
    makeRemoteTaskRunner(new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD));
    RemoteTaskRunnerWorkItem workItem = remoteTaskRunner.addPendingTask(task);
    remoteTaskRunner.runPendingTask(workItem);
    TaskStatus taskStatus = workItem.getResult().get(0, TimeUnit.MILLISECONDS);
    Assert.assertEquals(TaskState.FAILED, taskStatus.getStatusCode());
    Assert.assertNotNull(taskStatus.getErrorMsg());
    Assert.assertTrue(
        taskStatus.getErrorMsg().startsWith("The worker that this task is assigned did not start it in timeout")
    );
  }

  @Test
  public void testGetMaximumCapacity_noWorkerConfig()
  {
    httpClient = EasyMock.createMock(HttpClient.class);
    remoteTaskRunner = rtrTestUtils.makeRemoteTaskRunner(
        new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD),
        new TestProvisioningStrategy<>(),
        httpClient,
        null
    );
    Assert.assertEquals(-1, remoteTaskRunner.getMaximumCapacityWithAutoscale());
  }

  @Test
  public void testGetMaximumCapacity_noAutoScaler()
  {
    httpClient = EasyMock.createMock(HttpClient.class);
    remoteTaskRunner = rtrTestUtils.makeRemoteTaskRunner(
        new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD),
        new TestProvisioningStrategy<>(),
        httpClient,
        new DefaultWorkerBehaviorConfig(new EqualDistributionWorkerSelectStrategy(null, null), null)
    );
    Assert.assertEquals(-1, remoteTaskRunner.getMaximumCapacityWithAutoscale());
  }

  @Test
  public void testGetMaximumCapacity_withAutoScaler()
  {
    httpClient = EasyMock.createMock(HttpClient.class);
    remoteTaskRunner = rtrTestUtils.makeRemoteTaskRunner(
        new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD),
        new TestProvisioningStrategy<>(),
        httpClient,
        DefaultWorkerBehaviorConfig.defaultConfig()
    );
    // Default autoscaler has max workers of 0
    Assert.assertEquals(0, remoteTaskRunner.getMaximumCapacityWithAutoscale());
  }

  private void doSetup() throws Exception
  {
    makeWorker();
    makeRemoteTaskRunner(new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD));
  }

  private void makeRemoteTaskRunner(RemoteTaskRunnerConfig config)
  {
    httpClient = EasyMock.createMock(HttpClient.class);
    remoteTaskRunner = rtrTestUtils.makeRemoteTaskRunner(config, httpClient);
  }

  private void makeWorker() throws Exception
  {
    worker = rtrTestUtils.makeWorker(WORKER_HOST, 3);
  }

  private void disableWorker() throws Exception
  {
    rtrTestUtils.disableWorker(worker);
  }

  private boolean taskAnnounced(final String taskId)
  {
    return rtrTestUtils.taskAssigned(WORKER_HOST, taskId);
  }

  private boolean workerRunningTask(final String taskId)
  {
    return rtrTestUtils.workerRunningTask(WORKER_HOST, taskId);
  }

  private boolean workerCompletedTask(final ListenableFuture<TaskStatus> result)
  {
    return TestUtils.conditionValid(
        new IndexingServiceCondition()
        {
          @Override
          public boolean isValid()
          {
            return result.isDone();
          }
        }
    );
  }

  private void mockWorkerRunningTask(final Task task) throws Exception
  {
    rtrTestUtils.mockWorkerRunningTask("worker", task);
  }

  private void mockWorkerCompleteSuccessfulTask(final Task task) throws Exception
  {
    rtrTestUtils.mockWorkerCompleteSuccessfulTask("worker", task);
  }

  private void mockWorkerCompleteFailedTask(final Task task) throws Exception
  {
    rtrTestUtils.mockWorkerCompleteFailedTask("worker", task);
  }

  @Test
  public void testFindLazyWorkerTaskRunning() throws Exception
  {
    doSetup();
    remoteTaskRunner.start();
    remoteTaskRunner.run(task);
    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);
    Collection<Worker> lazyworkers = remoteTaskRunner.markWorkersLazy(
        new Predicate<>()
        {
          @Override
          public boolean apply(ImmutableWorkerInfo input)
          {
            return true;
          }
        }, 1
    );
    Assert.assertTrue(lazyworkers.isEmpty());
    Assert.assertTrue(remoteTaskRunner.getLazyWorkers().isEmpty());
    Assert.assertEquals(1, remoteTaskRunner.getWorkers().size());
  }

  @Test
  public void testFindLazyWorkerForWorkerJustAssignedTask() throws Exception
  {
    doSetup();
    remoteTaskRunner.run(task);
    Assert.assertTrue(taskAnnounced(task.getId()));
    Collection<Worker> lazyworkers = remoteTaskRunner.markWorkersLazy(
        new Predicate<>()
        {
          @Override
          public boolean apply(ImmutableWorkerInfo input)
          {
            return true;
          }
        }, 1
    );
    Assert.assertTrue(lazyworkers.isEmpty());
    Assert.assertTrue(remoteTaskRunner.getLazyWorkers().isEmpty());
    Assert.assertEquals(1, remoteTaskRunner.getWorkers().size());
  }

  @Test
  public void testFindLazyWorkerNotRunningAnyTask() throws Exception
  {
    doSetup();
    Collection<Worker> lazyworkers = remoteTaskRunner.markWorkersLazy(
        new Predicate<>()
        {
          @Override
          public boolean apply(ImmutableWorkerInfo input)
          {
            return true;
          }
        }, 1
    );
    Assert.assertEquals(1, lazyworkers.size());
    Assert.assertEquals(1, remoteTaskRunner.getLazyWorkers().size());
    Assert.assertEquals(3, remoteTaskRunner.getTotalTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
    Assert.assertFalse(remoteTaskRunner.getIdleTaskSlotCount().containsKey(WorkerConfig.DEFAULT_CATEGORY));
    Assert.assertEquals(3, remoteTaskRunner.getLazyTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue());
  }

  @Test
  public void testFindLazyWorkerNotRunningAnyTaskButWithZeroMaxWorkers() throws Exception
  {
    doSetup();
    Collection<Worker> lazyworkers = remoteTaskRunner.markWorkersLazy(
        new Predicate<>()
        {
          @Override
          public boolean apply(ImmutableWorkerInfo input)
          {
            return true;
          }
        }, 0
    );
    Assert.assertEquals(0, lazyworkers.size());
    Assert.assertEquals(0, remoteTaskRunner.getLazyWorkers().size());
  }

  @Test
  public void testWorkerZKReconnect() throws Exception
  {
    makeWorker();
    makeRemoteTaskRunner(new TestRemoteTaskRunnerConfig(new Period("PT5M")));
    Future<TaskStatus> future = remoteTaskRunner.run(task);

    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);

    Assert.assertTrue(workerRunningTask(task.getId()));
    byte[] bytes = cf.getData().forPath(ANNOUCEMENTS_PATH);
    cf.delete().forPath(ANNOUCEMENTS_PATH);
    // worker task cleanup scheduled
    Assert.assertTrue(
        TestUtils.conditionValid(
            new IndexingServiceCondition()
            {
              @Override
              public boolean isValid()
              {
                return remoteTaskRunner.getRemovedWorkerCleanups().containsKey(worker.getHost());
              }
            }
        )
    );

    // Worker got reconnected
    cf.create().forPath(ANNOUCEMENTS_PATH, bytes);

    // worker task cleanup should get cancelled and removed
    Assert.assertTrue(
        TestUtils.conditionValid(
            new IndexingServiceCondition()
            {
              @Override
              public boolean isValid()
              {
                return !remoteTaskRunner.getRemovedWorkerCleanups().containsKey(worker.getHost());
              }
            }
        )
    );

    mockWorkerCompleteSuccessfulTask(task);
    TaskStatus status = future.get();
    Assert.assertEquals(status.getStatusCode(), TaskState.SUCCESS);
    Assert.assertEquals(TaskState.SUCCESS, status.getStatusCode());
  }

  @Test
  public void testSortByInsertionTime()
  {
    RemoteTaskRunnerWorkItem item1 = new RemoteTaskRunnerWorkItem("b", "t", null, null, "ds_test")
        .withQueueInsertionTime(DateTimes.of("2015-01-01T00:00:03Z"));
    RemoteTaskRunnerWorkItem item2 = new RemoteTaskRunnerWorkItem("a", "t", null, null, "ds_test")
        .withQueueInsertionTime(DateTimes.of("2015-01-01T00:00:02Z"));
    RemoteTaskRunnerWorkItem item3 = new RemoteTaskRunnerWorkItem("c", "t", null, null, "ds_test")
        .withQueueInsertionTime(DateTimes.of("2015-01-01T00:00:01Z"));
    ArrayList<RemoteTaskRunnerWorkItem> workItems = Lists.newArrayList(item1, item2, item3);
    RemoteTaskRunner.sortByInsertionTime(workItems);
    Assert.assertEquals(item3, workItems.get(0));
    Assert.assertEquals(item2, workItems.get(1));
    Assert.assertEquals(item1, workItems.get(2));
  }

  @Test
  public void testBlacklistZKWorkers() throws Exception
  {
    makeWorker();

    RemoteTaskRunnerConfig rtrConfig = new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD);
    rtrConfig.setMaxPercentageBlacklistWorkers(100);

    makeRemoteTaskRunner(rtrConfig);

    TestIndexTask task1 = new TestIndexTask(
        "test_index1",
        new TaskResource("test_index1", 1),
        "foo",
        TaskStatus.success("test_index1"),
        jsonMapper
    );
    Future<TaskStatus> taskFuture1 = remoteTaskRunner.run(task1);
    Assert.assertTrue(taskAnnounced(task1.getId()));
    mockWorkerRunningTask(task1);
    mockWorkerCompleteFailedTask(task1);
    Assert.assertTrue(taskFuture1.get().isFailure());
    Assert.assertEquals(0, remoteTaskRunner.getBlackListedWorkers().size());
    Assert.assertEquals(
        1,
        remoteTaskRunner.findWorkerRunningTask(task1.getId()).getContinuouslyFailedTasksCount()
    );

    TestIndexTask task2 = new TestIndexTask(
        "test_index2",
        new TaskResource("test_index2", 1),
        "foo",
        TaskStatus.running("test_index2"),
        jsonMapper
    );
    Future<TaskStatus> taskFuture2 = remoteTaskRunner.run(task2);
    Assert.assertTrue(taskAnnounced(task2.getId()));
    mockWorkerRunningTask(task2);
    mockWorkerCompleteFailedTask(task2);
    Assert.assertTrue(taskFuture2.get().isFailure());
    Assert.assertEquals(1, remoteTaskRunner.getBlackListedWorkers().size());
    Assert.assertEquals(
        2,
        remoteTaskRunner.findWorkerRunningTask(task2.getId()).getContinuouslyFailedTasksCount()
    );

    ((RemoteTaskRunnerTestUtils.TestableRemoteTaskRunner) remoteTaskRunner)
        .setCurrentTimeMillis(System.currentTimeMillis());
    remoteTaskRunner.checkBlackListedNodes();

    Assert.assertEquals(1, remoteTaskRunner.getBlackListedWorkers().size());

    ((RemoteTaskRunnerTestUtils.TestableRemoteTaskRunner) remoteTaskRunner)
        .setCurrentTimeMillis(System.currentTimeMillis() + 2 * TIMEOUT_PERIOD.toStandardDuration().getMillis());
    remoteTaskRunner.checkBlackListedNodes();

    // After backOffTime the nodes are removed from blacklist
    Assert.assertEquals(0, remoteTaskRunner.getBlackListedWorkers().size());
    Assert.assertEquals(
        0,
        remoteTaskRunner.findWorkerRunningTask(task2.getId()).getContinuouslyFailedTasksCount()
    );

    TestIndexTask task3 = new TestIndexTask(
        "test_index3",
        new TaskResource("test_index3", 1),
        "foo",
        TaskStatus.running("test_index3"),
        jsonMapper
    );
    Future<TaskStatus> taskFuture3 = remoteTaskRunner.run(task3);
    Assert.assertTrue(taskAnnounced(task3.getId()));
    mockWorkerRunningTask(task3);
    mockWorkerCompleteSuccessfulTask(task3);
    Assert.assertTrue(taskFuture3.get().isSuccess());
    Assert.assertEquals(0, remoteTaskRunner.getBlackListedWorkers().size());
    Assert.assertEquals(
        0,
        remoteTaskRunner.findWorkerRunningTask(task3.getId()).getContinuouslyFailedTasksCount()
    );
  }

  /**
   * With 2 workers and maxPercentageBlacklistWorkers(25), no worker should be blacklisted even after exceeding
   * maxRetriesBeforeBlacklist.
   */
  @Test
  public void testBlacklistZKWorkers25Percent() throws Exception
  {
    rtrTestUtils.makeWorker("worker", 10);
    rtrTestUtils.makeWorker("worker2", 10);

    RemoteTaskRunnerConfig rtrConfig = new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD);
    rtrConfig.setMaxPercentageBlacklistWorkers(25);

    makeRemoteTaskRunner(rtrConfig);

    String assignedWorker = null;

    for (int i = 1; i < 13; i++) {
      String taskId = StringUtils.format("rt-%d", i);
      TestIndexTask task = new TestIndexTask(
          taskId,
          new TaskResource(taskId, 1),
          "foo",
          TaskStatus.success(taskId),
          jsonMapper
      );

      Future<TaskStatus> taskFuture = remoteTaskRunner.run(task);

      if (i == 1) {
        if (rtrTestUtils.taskAssigned("worker2", task.getId())) {
          assignedWorker = "worker2";
        } else {
          assignedWorker = "worker";
        }
      }

      Assert.assertTrue(rtrTestUtils.taskAssigned(assignedWorker, task.getId()));
      rtrTestUtils.mockWorkerRunningTask(assignedWorker, task);
      rtrTestUtils.mockWorkerCompleteFailedTask(assignedWorker, task);

      Assert.assertTrue(taskFuture.get().isFailure());
      Assert.assertEquals(0, remoteTaskRunner.getBlackListedWorkers().size());
      Assert.assertEquals(
          i,
          remoteTaskRunner.findWorkerId("worker").getContinuouslyFailedTasksCount()
          + remoteTaskRunner.findWorkerId("worker2").getContinuouslyFailedTasksCount()
      );
    }
  }

  /**
   * With 2 workers and maxPercentageBlacklistWorkers(50), one worker should get blacklisted after the second failure
   * and the second worker should never be blacklisted even after exceeding maxRetriesBeforeBlacklist.
   */
  @Test
  public void testBlacklistZKWorkers50Percent() throws Exception
  {
    rtrTestUtils.makeWorker("worker", 10);
    rtrTestUtils.makeWorker("worker2", 10);

    RemoteTaskRunnerConfig rtrConfig = new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD);
    rtrConfig.setMaxPercentageBlacklistWorkers(50);

    makeRemoteTaskRunner(rtrConfig);

    String firstWorker = null;
    String secondWorker = null;

    for (int i = 1; i < 13; i++) {
      String taskId = StringUtils.format("rt-%d", i);
      TestIndexTask task = new TestIndexTask(
          taskId,
          new TaskResource(taskId, 1),
          "foo",
          TaskStatus.success(taskId),
          jsonMapper
      );

      Future<TaskStatus> taskFuture = remoteTaskRunner.run(task);

      if (i == 1) {
        if (rtrTestUtils.taskAssigned("worker2", task.getId())) {
          firstWorker = "worker2";
          secondWorker = "worker";
        } else {
          firstWorker = "worker";
          secondWorker = "worker2";
        }
      }

      final String expectedWorker = i > 2 ? secondWorker : firstWorker;

      Assert.assertTrue(
          StringUtils.format("Task[%s] assigned to worker[%s]", i, expectedWorker),
          rtrTestUtils.taskAssigned(expectedWorker, task.getId())
      );
      rtrTestUtils.mockWorkerRunningTask(expectedWorker, task);
      rtrTestUtils.mockWorkerCompleteFailedTask(expectedWorker, task);

      Assert.assertTrue(taskFuture.get().isFailure());
      Assert.assertEquals(
          StringUtils.format("Blacklisted workers after task[%s]", i),
          i >= 2 ? 1 : 0,
          remoteTaskRunner.getBlackListedWorkers().size()
      );
      Assert.assertEquals(
          StringUtils.format("Continuously failed tasks after task[%s]", i),
          i,
          remoteTaskRunner.findWorkerId("worker").getContinuouslyFailedTasksCount()
          + remoteTaskRunner.findWorkerId("worker2").getContinuouslyFailedTasksCount()
      );
    }
  }

  @Test
  public void testSuccessfulTaskOnBlacklistedWorker() throws Exception
  {
    makeWorker();

    RemoteTaskRunnerConfig rtrConfig = new TestRemoteTaskRunnerConfig(TIMEOUT_PERIOD);
    rtrConfig.setMaxPercentageBlacklistWorkers(100);

    makeRemoteTaskRunner(rtrConfig);

    TestIndexTask task1 = new TestIndexTask(
        "test_index1", new TaskResource("test_index1", 1), "foo", TaskStatus.success("test_index1"), jsonMapper
    );
    TestIndexTask task2 = new TestIndexTask(
        "test_index2", new TaskResource("test_index2", 1), "foo", TaskStatus.success("test_index2"), jsonMapper
    );
    TestIndexTask task3 = new TestIndexTask(
        "test_index3", new TaskResource("test_index3", 1), "foo", TaskStatus.success("test_index3"), jsonMapper
    );

    Future<TaskStatus> taskFuture1 = remoteTaskRunner.run(task1);
    Assert.assertTrue(taskAnnounced(task1.getId()));
    mockWorkerRunningTask(task1);
    mockWorkerCompleteFailedTask(task1);
    Assert.assertTrue(taskFuture1.get().isFailure());
    Assert.assertEquals(0, remoteTaskRunner.getBlackListedWorkers().size());
    Assert.assertFalse(remoteTaskRunner.getBlacklistedTaskSlotCount().containsKey(WorkerConfig.DEFAULT_CATEGORY));

    Future<TaskStatus> taskFuture2 = remoteTaskRunner.run(task2);
    Assert.assertTrue(taskAnnounced(task2.getId()));
    mockWorkerRunningTask(task2);
    Assert.assertFalse(remoteTaskRunner.getBlacklistedTaskSlotCount().containsKey(WorkerConfig.DEFAULT_CATEGORY));

    Future<TaskStatus> taskFuture3 = remoteTaskRunner.run(task3);
    Assert.assertTrue(taskAnnounced(task3.getId()));
    mockWorkerRunningTask(task3);
    mockWorkerCompleteFailedTask(task3);
    Assert.assertTrue(taskFuture3.get().isFailure());
    Assert.assertEquals(1, remoteTaskRunner.getBlackListedWorkers().size());
    Assert.assertEquals(
        3,
        remoteTaskRunner.getBlacklistedTaskSlotCount().get(WorkerConfig.DEFAULT_CATEGORY).longValue()
    );

    mockWorkerCompleteSuccessfulTask(task2);
    Assert.assertTrue(taskFuture2.get().isSuccess());
    Assert.assertEquals(0, remoteTaskRunner.getBlackListedWorkers().size());
    Assert.assertFalse(remoteTaskRunner.getBlacklistedTaskSlotCount().containsKey(WorkerConfig.DEFAULT_CATEGORY));
  }

  @Test
  public void testStatusListenerEventDataNullShouldNotThrowException() throws Exception
  {
    // Set up mock emitter to verify log alert when exception is thrown inside the status listener
    Worker worker = EasyMock.createMock(Worker.class);
    EasyMock.expect(worker.getHost()).andReturn("host").atLeastOnce();
    EasyMock.replay(worker);
    ServiceEmitter emitter = EasyMock.createMock(ServiceEmitter.class);
    Capture<EmittingLogger.LoggingAlertBuilder> capturedArgument = Capture.newInstance();
    emitter.emit(EasyMock.capture(capturedArgument));
    EasyMock.expectLastCall().atLeastOnce();
    EmittingLogger.registerEmitter(emitter);
    EasyMock.replay(emitter);

    PathChildrenCache cache = new PathChildrenCache(cf, "/test", true);
    testStartWithNoWorker();
    cache.getListenable()
         .addListener(remoteTaskRunner.getStatusListener(worker, new ZkWorker(worker, cache, jsonMapper), null));
    cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

    // Status listener will recieve event with null data
    Assert.assertTrue(
        TestUtils.conditionValid(() -> cache.getCurrentData().size() == 1)
    );

    // Verify that the log emitter was called
    EasyMock.verify(worker);
    EasyMock.verify(emitter);
    Map<String, Object> alertDataMap = capturedArgument.getValue().build(null).getDataMap();
    Assert.assertTrue(alertDataMap.containsKey("znode"));
    Assert.assertNull(alertDataMap.get("znode"));
    // Status listener should successfully completes without throwing exception
  }

  @Test
  public void testStreamTaskReportsUnknownTask() throws Exception
  {
    doSetup();
    Assert.assertEquals(Optional.absent(), remoteTaskRunner.streamTaskReports("foo"));
  }

  @Test
  public void testStreamTaskReportsKnownTask() throws Exception
  {
    doSetup();
    final Capture<Request> capturedRequest = Capture.newInstance();
    final String reportString = "my report!";
    final ByteArrayInputStream reportResponse = new ByteArrayInputStream(StringUtils.toUtf8(reportString));
    EasyMock.expect(httpClient.go(EasyMock.capture(capturedRequest), EasyMock.anyObject()))
            .andReturn(Futures.immediateFuture(reportResponse));
    EasyMock.replay(httpClient);

    ListenableFuture<TaskStatus> result = remoteTaskRunner.run(task);
    Assert.assertTrue(taskAnnounced(task.getId()));
    mockWorkerRunningTask(task);

    // Wait for the task to have a known location.
    Assert.assertTrue(
        TestUtils.conditionValid(
            () ->
                !remoteTaskRunner.getRunningTasks().isEmpty()
                && !Iterables.getOnlyElement(remoteTaskRunner.getRunningTasks())
                             .getLocation()
                             .equals(TaskLocation.unknown())
        )
    );

    // Stream task reports from a running task.
    final InputStream in = remoteTaskRunner.streamTaskReports(task.getId()).get();
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ByteStreams.copy(in, baos);
    Assert.assertEquals(reportString, StringUtils.fromUtf8(baos.toByteArray()));

    // Stream task reports from a completed task.
    mockWorkerCompleteSuccessfulTask(task);
    Assert.assertTrue(workerCompletedTask(result));
    Assert.assertEquals(Optional.absent(), remoteTaskRunner.streamTaskReports(task.getId()));

    // Verify the HTTP request.
    EasyMock.verify(httpClient);
    Assert.assertEquals(
        "http://dummy:9000/druid/worker/v1/chat/task%20id%20with%20spaces/liveReports",
        capturedRequest.getValue().getUrl().toString()
    );
  }

  @Test
  public void testBuildPublishAction()
  {
    TestIndexTask task = new TestIndexTask(
        "test_index1",
        new TaskResource("test_index1", 1),
        "foo",
        TaskStatus.success("test_index1"),
        jsonMapper
    );

    Assert.assertEquals(
        SegmentTransactionalAppendAction.class,
        task.buildPublishActionForTest(
            Collections.emptySet(),
            Collections.emptySet(),
            null,
            TaskLockType.APPEND
        ).getClass()
    );

    Assert.assertEquals(
        SegmentTransactionalReplaceAction.class,
        task.buildPublishActionForTest(
            Collections.emptySet(),
            Collections.emptySet(),
            null,
            TaskLockType.REPLACE
        ).getClass()
    );

    Assert.assertEquals(
        SegmentTransactionalInsertAction.class,
        task.buildPublishActionForTest(
            Collections.emptySet(),
            Collections.emptySet(),
            null,
            TaskLockType.EXCLUSIVE
        ).getClass()
    );
  }
}
