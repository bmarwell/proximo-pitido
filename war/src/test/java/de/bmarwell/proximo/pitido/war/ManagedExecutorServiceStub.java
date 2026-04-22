/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * ${PROJECT_HOME}/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 */
package de.bmarwell.proximo.pitido.war;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.enterprise.concurrent.ManagedExecutorService;

/**
 * Plain-JUnit test stub for {@link ManagedExecutorService}.
 * Delegates all {@link ExecutorService} methods to a virtual-thread-per-task executor.
 * No container context is available outside the Liberty runtime.
 */
public class ManagedExecutorServiceStub implements ManagedExecutorService {

    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void execute(Runnable command) {
        this.delegate.execute(command);
    }

    @Override
    public void shutdown() {
        this.delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return this.delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return this.delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return this.delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return this.delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return this.delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return this.delegate.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return this.delegate.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return this.delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return this.delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return this.delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return this.delegate.invokeAny(tasks, timeout, unit);
    }
}
