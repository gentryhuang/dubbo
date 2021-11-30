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
package org.apache.dubbo.common.threadpool;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 概述：
 * 1 ThreadlessExecutor 是一种特殊类型的线程池，与一般线程最主要的区别是不管理任何线程。
 * 2 一般执行器会进行任务调度，但是通过 {@link #execute(Runnable)} 提交给这个执行器的任务不会被调度到特定的线程，
 * 3 这些任务存储在阻塞队列中，只有当线程调用 {@link #waitAndDrain()} 时才会执行，执行该任务的线程和调用 {@link #waitAndDrain()} 方法的线程时同一个线程。
 * 出现原因：
 * 1 在 Dubbo 2.7.5 版本之前，在 WrappedChannelHandler 中会为每个连接都创建一个线程池，不会根据 URL 复用同一线程池，而是通过 SPI 找到 ThreadPool 扩展实现创建新的线程池。新版则会根据 端点和服务端口缓存线程池，摒弃了以连接维度创建线程池。
 * 2 线程池按照连接隔离，即每个连接独享一个线程池。这样，当面临需要消费大量服务且并发数比较大的场景时，如 网关类场景，可能会导致消费端线程个数不断增加，CPU飙高，创建线程过多而导致OOM
 * 3
 */
public class ThreadlessExecutor extends AbstractExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(ThreadlessExecutor.class.getName());

    /**
     * 阻塞队列，用来在 IO线程和业务线程之间传递任务
     */
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    /**
     * ThreadlessExecutor 底层关联的共享线程池。当业务线程已经不再等待响应时，会由该共享线程执行提交任务。
     */
    private ExecutorService sharedExecutor;
    /**
     * 指向请求对应的 DefaultFuture
     */
    private CompletableFuture<?> waitingFuture;

    /**
     * finished 和 waiting 字段控制着等待任务的处理
     */
    private boolean finished = false;
    private volatile boolean waiting = true;

    private final Object lock = new Object();

    public ThreadlessExecutor(ExecutorService sharedExecutor) {
        this.sharedExecutor = sharedExecutor;
    }

    public CompletableFuture<?> getWaitingFuture() {
        return waitingFuture;
    }

    public void setWaitingFuture(CompletableFuture<?> waitingFuture) {
        this.waitingFuture = waitingFuture;
    }

    public boolean isWaiting() {
        return waiting;
    }

    /**
     * waitAndDrain() 方法一般与一次 RPC 调用绑定，只会执行一次。当后续再次调用该方法时，会检查 finished 的值，若为 true ，则此次调用直接返回。
     * 后续再次调用 execute() 方法提交任务时，会根据 waiting 字段决定任务是放入 queue 队列等待业务线程执行，还是直接由 sharedExecutor 线程池执行。
     *
     * @throws InterruptedException
     */
    public void waitAndDrain() throws InterruptedException {
        /**
         * Usually, {@link #waitAndDrain()} will only get called once. It blocks for the response for the first time,
         * once the response (the task) reached and being executed waitAndDrain will return, the whole request process
         * then finishes. Subsequent calls on {@link #waitAndDrain()} (if there're any) should return immediately.
         *
         * There's no need to worry that {@link #finished} is not thread-safe. Checking and updating of
         * 'finished' only appear in waitAndDrain, since waitAndDrain is binding to one RPC call (one thread), the call
         * of it is totally sequential.
         */
        if (finished) { // 检测当前 ThreadlessExecutor 状态
            return;
        }

        // 获取阻塞队列中的任务
        Runnable runnable = queue.take();

        synchronized (lock) {
            // 修改waiting 状态
            waiting = false;
            // 执行任务
            runnable.run();
        }

        // 如果阻塞队列还有其它任务，则需要一起执行
        runnable = queue.poll();
        while (runnable != null) {
            try {
                runnable.run();
            } catch (Throwable t) {
                logger.info(t);

            }
            runnable = queue.poll();
        }
        // 标记 ThreadlessExecutor 是完成状态
        finished = true;
    }

    public long waitAndDrain(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        /*long startInMs = System.currentTimeMillis();
        Runnable runnable = queue.poll(timeout, unit);
        if (runnable == null) {
            throw new TimeoutException();
        }
        runnable.run();
        long elapsedInMs = System.currentTimeMillis() - startInMs;
        long timeLeft = timeout - elapsedInMs;
        if (timeLeft < 0) {
            throw new TimeoutException();
        }
        return timeLeft;*/
        throw new UnsupportedOperationException();
    }

    /**
     * If the calling thread is still waiting for a callback task, add the task into the blocking queue to wait for schedule.
     * Otherwise, submit to shared callback executor directly.
     *
     * @param runnable
     */
    @Override
    public void execute(Runnable runnable) {
        synchronized (lock) {
            // 判断业务线程是否还在等待响应，不等待则直接交给共享线程池处理
            if (!waiting) {
                sharedExecutor.execute(runnable);

                // 业务线程还在等待，则将任务写入队列，最终由业务线程自己执行（业务线程在 waitAndDrain 方法上等待任务）
            } else {
                queue.add(runnable);
            }
        }
    }

    /**
     * 通知阻塞 {@link #waitAndDrain()} 的线程返回，避免调用出现异常还傻傻地等待
     */
    public void notifyReturn(Throwable t) {
        // an empty runnable task.
        execute(() -> {
            waitingFuture.completeExceptionally(t);
        });
    }

    /**
     * The following methods are still not supported
     */

    @Override
    public void shutdown() {
        shutdownNow();
    }

    @Override
    public List<Runnable> shutdownNow() {
        notifyReturn(new IllegalStateException("Consumer is shutting down and this call is going to be stopped without " +
                "receiving any result, usually this is called by a slow provider instance or bad service implementation."));
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return false;
    }
}
