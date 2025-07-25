/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.HugeGraphParams;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.page.PageInfo;
import org.apache.hugegraph.backend.query.Condition;
import org.apache.hugegraph.backend.query.ConditionQuery;
import org.apache.hugegraph.backend.query.QueryResults;
import org.apache.hugegraph.backend.store.BackendStore;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.exception.ConnectionException;
import org.apache.hugegraph.exception.NotFoundException;
import org.apache.hugegraph.iterator.ExtendableIterator;
import org.apache.hugegraph.iterator.MapperIterator;
import org.apache.hugegraph.job.EphemeralJob;
import org.apache.hugegraph.schema.PropertyKey;
import org.apache.hugegraph.schema.VertexLabel;
import org.apache.hugegraph.structure.HugeVertex;
import org.apache.hugegraph.task.HugeTask.P;
import org.apache.hugegraph.task.TaskCallable.SysTaskCallable;
import org.apache.hugegraph.task.TaskManager.ContextCallable;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.HugeKeys;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableMap;

public class StandardTaskScheduler implements TaskScheduler {

    private static final Logger LOG = Log.logger(StandardTaskScheduler.class);

    private final HugeGraphParams graph;
    private final ServerInfoManager serverManager;

    private final ExecutorService taskExecutor;
    private final ExecutorService taskDbExecutor;

    private final Map<Id, HugeTask<?>> tasks;

    private volatile TaskTransaction taskTx;

    public StandardTaskScheduler(HugeGraphParams graph,
                                 ExecutorService taskExecutor,
                                 ExecutorService taskDbExecutor,
                                 ExecutorService serverInfoDbExecutor) {
        E.checkNotNull(graph, "graph");
        E.checkNotNull(taskExecutor, "taskExecutor");
        E.checkNotNull(taskDbExecutor, "dbExecutor");

        this.graph = graph;
        this.taskExecutor = taskExecutor;
        this.taskDbExecutor = taskDbExecutor;

        this.serverManager = new ServerInfoManager(graph, serverInfoDbExecutor);
        this.tasks = new ConcurrentHashMap<>();

        this.taskTx = null;
    }

    @Override
    public HugeGraph graph() {
        return this.graph.graph();
    }

    @Override
    public String graphName() {
        return this.graph.name();
    }

    @Override
    public int pendingTasks() {
        return this.tasks.size();
    }

    private TaskTransaction tx() {
        // NOTE: only the owner thread can access task tx
        if (this.taskTx == null) {
            /*
             * NOTE: don't synchronized(this) due to scheduler thread hold
             * this lock through scheduleTasks(), then query tasks and wait
             * for db-worker thread after call(), the tx may not be initialized
             * but can't catch this lock, then cause deadlock.
             * We just use this.serverManager as a monitor here
             */
            synchronized (this.serverManager) {
                if (this.taskTx == null) {
                    BackendStore store = this.graph.loadSystemStore();
                    TaskTransaction tx = new TaskTransaction(this.graph, store);
                    assert this.taskTx == null; // maybe reentrant?
                    this.taskTx = tx;
                }
            }
        }
        assert this.taskTx != null;
        return this.taskTx;
    }

    @Override
    public <V> void restoreTasks() {
        Id selfServer = this.serverManager().selfNodeId();
        List<HugeTask<V>> taskList = new ArrayList<>();
        // Restore 'RESTORING', 'RUNNING' and 'QUEUED' tasks in order.
        for (TaskStatus status : TaskStatus.PENDING_STATUSES) {
            String page = this.supportsPaging() ? PageInfo.PAGE_NONE : null;
            do {
                Iterator<HugeTask<V>> iter;
                for (iter = this.findTask(status, PAGE_SIZE, page);
                     iter.hasNext(); ) {
                    HugeTask<V> task = iter.next();
                    if (selfServer.equals(task.server())) {
                        taskList.add(task);
                    }
                }
                if (page != null) {
                    page = PageInfo.pageInfo(iter);
                }
            } while (page != null);
        }
        for (HugeTask<V> task : taskList) {
            LOG.info("restore task {}", task);
            this.restore(task);
        }
        try {
            this.graph.graphTransaction().commit();
        }
        finally {
            this.graph.closeTx();
        }
    }

    private <V> Future<?> restore(HugeTask<V> task) {
        E.checkArgumentNotNull(task, "Task can't be null");
        E.checkArgument(!this.tasks.containsKey(task.id()),
                        "Task '%s' is already in the queue", task.id());
        E.checkArgument(!task.isDone() && !task.completed(),
                        "No need to restore completed task '%s' with status %s",
                        task.id(), task.status());
        task.status(TaskStatus.RESTORING);
        task.retry();
        return this.submitTask(task);
    }

    @Override
    public <V> Future<?> schedule(HugeTask<V> task) {
        E.checkArgumentNotNull(task, "Task can't be null");

        /*
         * Just submit to queue if status=QUEUED (means re-schedule task)
         * NOTE: schedule() method may be called multi times by
         * HugeTask.checkDependenciesSuccess() method
         */
        if (task.status() == TaskStatus.QUEUED) {
            return this.resubmitTask(task);
        }

        /*
         * Due to EphemeralJob won't be serialized and deserialized through
         * shared storage, submit EphemeralJob immediately on any node
         */
        if (task.callable() instanceof EphemeralJob) {
            // NOTE: we don't need to save EphemeralJob task
            task.status(TaskStatus.QUEUED);
            return this.submitTask(task);
        }

        // Check this is on master for normal task schedule
        this.checkOnMasterNode("schedule");

        if (this.serverManager().onlySingleNode() && !task.computer()) {
            /*
             * Speed up for single node, submit the task immediately,
             * this code can be removed without affecting code logic
             */
            task.status(TaskStatus.QUEUED);
            task.server(this.serverManager().selfNodeId());
            this.save(task);
            return this.submitTask(task);
        } else {
            /*
             * Just set the SCHEDULING status and save the task,
             * it will be scheduled by periodic scheduler worker
             */
            task.status(TaskStatus.SCHEDULING);
            this.save(task);

            // Notify master server to schedule and execute immediately
            TaskManager.instance().notifyNewTask(task);

            return task;
        }
    }

    private <V> Future<?> submitTask(HugeTask<V> task) {
        int size = this.tasks.size() + 1;
        E.checkArgument(size <= MAX_PENDING_TASKS,
                        "Pending tasks size %s has exceeded the max limit %s",
                        size, MAX_PENDING_TASKS);
        this.initTaskCallable(task);
        assert !this.tasks.containsKey(task.id()) : task;
        this.tasks.put(task.id(), task);
        return this.taskExecutor.submit(task);
    }

    private <V> Future<?> resubmitTask(HugeTask<V> task) {
        E.checkArgument(task.status() == TaskStatus.QUEUED,
                        "Can't resubmit task '%s' with status %s",
                        task.id(), TaskStatus.QUEUED);
        E.checkArgument(this.tasks.containsKey(task.id()),
                        "Can't resubmit task '%s' not been submitted before",
                        task.id());
        return this.taskExecutor.submit(task);
    }

    public <V> void initTaskCallable(HugeTask<V> task) {
        task.scheduler(this);

        TaskCallable<V> callable = task.callable();
        callable.task(task);
        callable.graph(this.graph());
        if (callable instanceof SysTaskCallable) {
            // Only authorized to the necessary tasks
            ((SysTaskCallable<V>) callable).params(this.graph);
        }
    }

    @Override
    public synchronized <V> void cancel(HugeTask<V> task) {
        E.checkArgumentNotNull(task, "Task can't be null");
        this.checkOnMasterNode("cancel");

        if (task.completed() || task.cancelling()) {
            return;
        }

        LOG.info("Cancel task '{}' in status {}", task.id(), task.status());

        if (task.server() == null) {
            // The task not scheduled to workers, set canceled immediately
            assert task.status().code() < TaskStatus.QUEUED.code();
            if (task.status(TaskStatus.CANCELLED)) {
                this.save(task);
                return;
            }
        } else if (task.status(TaskStatus.CANCELLING)) {
            // The task scheduled to workers, let the worker node to cancel
            this.save(task);
            assert task.server() != null : task;
            assert this.serverManager().selfIsMaster();
            if (!task.server().equals(this.serverManager().selfNodeId())) {
                /*
                 * Remove the task from memory if it's running on worker node,
                 * but keep the task in memory if it's running on master node.
                 * Cancel-scheduling will read the task from backend store, if
                 * removed this instance from memory, there will be two task
                 * instances with the same id, and can't cancel the real task that
                 * is running but removed from memory.
                 */
                this.remove(task);
            }
            // Notify master server to schedule and execute immediately
            TaskManager.instance().notifyNewTask(task);
            return;
        }

        throw new HugeException("Can't cancel task '%s' in status %s",
                                task.id(), task.status());
    }

    @Override
    public ServerInfoManager serverManager() {
        return this.serverManager;
    }

    protected synchronized void scheduleTasksOnMaster() {
        // Master server schedule all scheduling tasks to suitable worker nodes
        Collection<HugeServerInfo> serverInfos = this.serverManager().allServerInfos();
        String page = this.supportsPaging() ? PageInfo.PAGE_NONE : null;
        do {
            Iterator<HugeTask<Object>> tasks = this.tasks(TaskStatus.SCHEDULING, PAGE_SIZE, page);
            while (tasks.hasNext()) {
                HugeTask<?> task = tasks.next();
                if (task.server() != null) {
                    // Skip if already scheduled
                    continue;
                }

                if (!this.serverManager.selfIsMaster()) {
                    return;
                }

                HugeServerInfo server = this.serverManager().pickWorkerNode(serverInfos, task);
                if (server == null) {
                    LOG.info("The master can't find suitable servers to " +
                             "execute task '{}', wait for next schedule", task.id());
                    continue;
                }

                // Found suitable server, update task status
                assert server.id() != null;
                task.server(server.id());
                task.status(TaskStatus.SCHEDULED);
                this.save(task);

                // Update server load in memory, it will be saved at the ending
                server.increaseLoad(task.load());

                LOG.info("Scheduled task '{}' to server '{}'", task.id(), server.id());
            }
            if (page != null) {
                page = PageInfo.pageInfo(tasks);
            }
        } while (page != null);

        // Save to store
        this.serverManager().updateServerInfos(serverInfos);
    }

    protected void executeTasksOnWorker(Id server) {
        String page = this.supportsPaging() ? PageInfo.PAGE_NONE : null;
        do {
            Iterator<HugeTask<Object>> tasks = this.tasks(TaskStatus.SCHEDULED, PAGE_SIZE, page);
            while (tasks.hasNext()) {
                HugeTask<?> task = tasks.next();
                this.initTaskCallable(task);
                Id taskServer = task.server();
                if (taskServer == null) {
                    LOG.warn("Task '{}' may not be scheduled", task.id());
                    continue;
                }
                HugeTask<?> memTask = this.tasks.get(task.id());
                if (memTask != null) {
                    assert memTask.status().code() > task.status().code();
                    continue;
                }
                if (taskServer.equals(server)) {
                    task.status(TaskStatus.QUEUED);
                    this.save(task);
                    this.submitTask(task);
                }
            }
            if (page != null) {
                page = PageInfo.pageInfo(tasks);
            }
        } while (page != null);
    }

    protected void cancelTasksOnWorker(Id server) {
        String page = this.supportsPaging() ? PageInfo.PAGE_NONE : null;
        do {
            Iterator<HugeTask<Object>> tasks = this.tasks(TaskStatus.CANCELLING, PAGE_SIZE, page);
            while (tasks.hasNext()) {
                HugeTask<?> task = tasks.next();
                Id taskServer = task.server();
                if (taskServer == null) {
                    LOG.warn("Task '{}' may not be scheduled", task.id());
                    continue;
                }
                if (!taskServer.equals(server)) {
                    continue;
                }
                /*
                 * Task may be loaded from backend store and not initialized.
                 * like: A task is completed but failed to save in the last
                 * step, resulting in the status of the task not being
                 * updated to storage, the task is not in memory, so it's not
                 * initialized when canceled.
                 */
                HugeTask<?> memTask = this.tasks.get(task.id());
                if (memTask != null) {
                    task = memTask;
                } else {
                    this.initTaskCallable(task);
                }
                boolean cancelled = task.cancel(true);
                LOG.info("Server '{}' cancel task '{}' with cancelled={}",
                         server, task.id(), cancelled);
            }
            if (page != null) {
                page = PageInfo.pageInfo(tasks);
            }
        } while (page != null);
    }

    @Override
    public void taskDone(HugeTask<?> task) {
        this.remove(task);

        Id selfServerId = this.serverManager().selfNodeId();
        try {
            this.serverManager().decreaseLoad(task.load());
        } catch (Throwable e) {
            LOG.error("Failed to decrease load for task '{}' on server '{}'",
                      task.id(), selfServerId, e);
        }
        LOG.debug("Task '{}' done on server '{}'", task.id(), selfServerId);
    }

    protected void remove(HugeTask<?> task) {
        this.remove(task, false);
    }

    protected void remove(HugeTask<?> task, boolean force) {
        E.checkNotNull(task, "remove task");
        HugeTask<?> delTask = this.tasks.remove(task.id());
        if (delTask != null && delTask != task) {
            LOG.warn("Task '{}' may be inconsistent status {}(expect {})",
                     task.id(), task.status(), delTask.status());
        }
        assert force || delTask == null || delTask.completed() ||
               delTask.cancelling() || delTask.isCancelled() : delTask;
    }

    @Override
    public <V> void save(HugeTask<V> task) {
        LOG.debug("Saving task: {}", task);
        task.scheduler(this);
        E.checkArgumentNotNull(task, "Task can't be null");
        this.call(() -> {
            // Construct vertex from task
            HugeVertex vertex = this.tx().constructVertex(task);
            // Delete index of old vertex to avoid stale index
            this.tx().deleteIndex(vertex);
            // Add or update task info to backend store
            return this.tx().addVertex(vertex);
        });
    }

    @Override
    public void init() {
        this.call(() -> this.tx().initSchema());
    }

    @Override
    public boolean close() {
        if (!this.taskDbExecutor.isShutdown()) {
            this.call(() -> {
                try {
                    this.tx().close();
                } catch (ConnectionException ignored) {
                    // ConnectionException means no connection established
                }
                this.graph.closeTx();
            });
        }
        return this.serverManager.close();
    }

    @Override
    public <V> HugeTask<V> task(Id id) {
        E.checkArgumentNotNull(id, "Parameter task id can't be null");
        @SuppressWarnings("unchecked")
        HugeTask<V> task = (HugeTask<V>) this.tasks.get(id);
        if (task != null) {
            return task;
        }
        return this.findTask(id);
    }

    @Override
    public <V> Iterator<HugeTask<V>> tasks(List<Id> ids) {
        List<Id> taskIdsNotInMem = new ArrayList<>();
        List<HugeTask<V>> taskInMem = new ArrayList<>();
        for (Id id : ids) {
            @SuppressWarnings("unchecked")
            HugeTask<V> task = (HugeTask<V>) this.tasks.get(id);
            if (task != null) {
                taskInMem.add(task);
            } else {
                taskIdsNotInMem.add(id);
            }
        }
        ExtendableIterator<HugeTask<V>> iterator;
        if (taskInMem.isEmpty()) {
            iterator = new ExtendableIterator<>();
        } else {
            iterator = new ExtendableIterator<>(taskInMem.iterator());
        }
        iterator.extend(this.findTasks(taskIdsNotInMem));
        return iterator;
    }

    @Override
    public <V> Iterator<HugeTask<V>> tasks(TaskStatus status,
                                           long limit, String page) {
        if (status == null) {
            return this.findAllTask(limit, page);
        }
        return this.findTask(status, limit, page);
    }

    public <V> HugeTask<V> findTask(Id id) {
        HugeTask<V> result =  this.call(() -> {
            Iterator<Vertex> vertices = this.tx().queryTaskInfos(id);
            Vertex vertex = QueryResults.one(vertices);
            if (vertex == null) {
                return null;
            }
            return HugeTask.fromVertex(vertex);
        });
        if (result == null) {
            throw new NotFoundException("Can't find task with id '%s'", id);
        }
        return result;
    }

    public <V> Iterator<HugeTask<V>> findTasks(List<Id> ids) {
        return this.queryTask(ids);
    }

    public <V> Iterator<HugeTask<V>> findAllTask(long limit, String page) {
        return this.queryTask(ImmutableMap.of(), limit, page);
    }

    public <V> Iterator<HugeTask<V>> findTask(TaskStatus status,
                                              long limit, String page) {
        return this.queryTask(P.STATUS, status.code(), limit, page);
    }

    @Override
    public <V> HugeTask<V> delete(Id id, boolean force) {
        this.checkOnMasterNode("delete");

        HugeTask<?> task = this.task(id);
        /*
         * The following is out of date when the task running on worker node:
         * HugeTask<?> task = this.tasks.get(id);
         * Tasks are removed from memory after completed at most time,
         * but there is a tiny gap between tasks is completed and
         * removed from memory.
         * We assume tasks only in memory may be incomplete status,
         * in fact, it is also possible to appear on the backend tasks
         * when the database status is inconsistent.
         */
        if (task != null) {
            E.checkArgument(force || task.completed(),
                            "Can't delete incomplete task '%s' in status %s" +
                            ", Please try to cancel the task first",
                            id, task.status());
            this.remove(task, force);
        }

        return this.call(() -> {
            Iterator<Vertex> vertices = this.tx().queryTaskInfos(id);
            HugeVertex vertex = (HugeVertex) QueryResults.one(vertices);
            if (vertex == null) {
                return null;
            }
            HugeTask<V> result = HugeTask.fromVertex(vertex);
            E.checkState(force || result.completed(),
                         "Can't delete incomplete task '%s' in status %s",
                         id, result.status());
            this.tx().removeVertex(vertex);
            return result;
        });
    }

    @Override
    public <V> HugeTask<V> waitUntilTaskCompleted(Id id, long seconds)
            throws TimeoutException {
        return this.waitUntilTaskCompleted(id, seconds, QUERY_INTERVAL);
    }

    @Override
    public <V> HugeTask<V> waitUntilTaskCompleted(Id id)
            throws TimeoutException {
        // This method is just used by tests
        long timeout = this.graph.configuration()
                                 .get(CoreOptions.TASK_WAIT_TIMEOUT);
        return this.waitUntilTaskCompleted(id, timeout, 1L);
    }

    private <V> HugeTask<V> waitUntilTaskCompleted(Id id, long seconds,
                                                   long intervalMs)
            throws TimeoutException {
        long passes = seconds * 1000 / intervalMs;
        HugeTask<V> task = null;
        for (long pass = 0; ; pass++) {
            try {
                task = this.task(id);
            } catch (NotFoundException e) {
                if (task != null && task.completed()) {
                    assert task.id().asLong() < 0L : task.id();
                    sleep(intervalMs);
                    return task;
                }
                throw e;
            }
            if (task.completed()) {
                // Wait for the task result being set after the status is completed
                sleep(intervalMs);
                return task;
            }
            if (pass >= passes) {
                break;
            }
            sleep(intervalMs);
        }
        throw new TimeoutException(String.format(
                "Task '%s' was not completed in %s seconds", id, seconds));
    }

    @Override
    public void waitUntilAllTasksCompleted(long seconds)
            throws TimeoutException {
        long passes = seconds * 1000 / QUERY_INTERVAL;
        int taskSize;
        for (long pass = 0; ; pass++) {
            taskSize = this.pendingTasks();
            if (taskSize == 0) {
                sleep(QUERY_INTERVAL);
                return;
            }
            if (pass >= passes) {
                break;
            }
            sleep(QUERY_INTERVAL);
        }
        throw new TimeoutException(String.format(
                "There are still %s incomplete tasks after %s seconds",
                taskSize, seconds));
    }

    @Override
    public void checkRequirement(String op) {
        this.checkOnMasterNode(op);
    }

    private <V> Iterator<HugeTask<V>> queryTask(String key, Object value,
                                                long limit, String page) {
        return this.queryTask(ImmutableMap.of(key, value), limit, page);
    }

    private <V> Iterator<HugeTask<V>> queryTask(Map<String, Object> conditions,
                                                long limit, String page) {
        return this.call(() -> {
            ConditionQuery query;
            if (this.graph.backendStoreFeatures().supportsTaskAndServerVertex()) {
                query = new ConditionQuery(HugeType.TASK);
            } else {
                query = new ConditionQuery(HugeType.VERTEX);
            }
            if (page != null) {
                query.page(page);
            }
            VertexLabel vl = this.graph().vertexLabel(P.TASK);
            query.eq(HugeKeys.LABEL, vl.id());
            for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                PropertyKey pk = this.graph().propertyKey(entry.getKey());
                query.query(Condition.eq(pk.id(), entry.getValue()));
            }
            query.showHidden(true);
            if (limit != NO_LIMIT) {
                query.limit(limit);
            }
            Iterator<Vertex> vertices = this.tx().queryVertices(query);
            Iterator<HugeTask<V>> tasks =
                    new MapperIterator<>(vertices, HugeTask::fromVertex);
            // Convert iterator to list to avoid across thread tx accessed
            return QueryResults.toList(tasks);
        });
    }

    private <V> Iterator<HugeTask<V>> queryTask(List<Id> ids) {
        return this.call(() -> {
            Object[] idArray = ids.toArray(new Id[0]);
            Iterator<Vertex> vertices = this.tx().queryTaskInfos(idArray);
            Iterator<HugeTask<V>> tasks =
                    new MapperIterator<>(vertices, HugeTask::fromVertex);
            // Convert iterator to list to avoid across thread tx accessed
            return QueryResults.toList(tasks);
        });
    }

    @Override
    public <V> V call(Runnable runnable) {
        return this.call(Executors.callable(runnable, null));
    }

    @Override
    public <V> V call(Callable<V> callable) {
        assert !Thread.currentThread().getName().startsWith(
                "task-db-worker") : "can't call by itself";
        try {
            // Pass task context for db thread
            callable = new ContextCallable<>(callable);
            // Ensure all db operations are executed in dbExecutor thread(s)
            return this.taskDbExecutor.submit(callable).get();
        } catch (Throwable e) {
            throw new HugeException("Failed to update/query TaskStore: %s",
                                    e, e.toString());
        }
    }

    private void checkOnMasterNode(String op) {
        if (!this.serverManager().selfIsMaster()) {
            throw new HugeException("Can't %s task on non-master server", op);
        }
    }

    private boolean supportsPaging() {
        return this.graph.backendStoreFeatures().supportsQueryByPage();
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ignored) {
            // Ignore InterruptedException
            return false;
        }
    }
}
