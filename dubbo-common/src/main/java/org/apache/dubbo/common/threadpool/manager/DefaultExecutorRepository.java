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
package org.apache.dubbo.common.threadpool.manager;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.utils.NamedThreadFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.EXECUTOR_SERVICE_COMPONENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;

/**
 * Consider implementing {@code Licycle} to enable executors shutdown when the process stops.
 */
public class DefaultExecutorRepository implements ExecutorRepository {
    private static final Logger logger = LoggerFactory.getLogger(DefaultExecutorRepository.class);

    private int DEFAULT_SCHEDULER_SIZE = Runtime.getRuntime().availableProcessors();

    private final ExecutorService SHARED_EXECUTOR = Executors.newCachedThreadPool(new NamedThreadFactory("DubboSharedHandler", true));

    private Ring<ScheduledExecutorService> scheduledExecutors = new Ring<>();

    private ScheduledExecutorService serviceExporterExecutor;

    private ScheduledExecutorService reconnectScheduledExecutor;

    /**
     * 缓存已有的线程池
     * key1: 表示线程池属于服务端侧还是消费端侧
     * key2: 线程池关联服务的端口
     * value: 线程池
     */
    private ConcurrentMap<String, ConcurrentMap<Integer, ExecutorService>> data = new ConcurrentHashMap<>();

    public DefaultExecutorRepository() {
//        for (int i = 0; i < DEFAULT_SCHEDULER_SIZE; i++) {
//            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Dubbo-framework-scheduler"));
//            scheduledExecutors.addItem(scheduler);
//        }
//
//        reconnectScheduledExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Dubbo-reconnect-scheduler"));
        serviceExporterExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Dubbo-exporter-scheduler"));
    }

    /**
     * 当服务或客户端初始化时，根据 URL 参数创建相应的线程池并缓存在合适的位置
     * Get called when the server or client instance initiating.
     *
     * @param url
     * @return
     */
    public synchronized ExecutorService createExecutorIfAbsent(URL url) {
        // 1 根据 URL 中的 side 参数值确定线程池缓存的第一层 key
        String componentKey = EXECUTOR_SERVICE_COMPONENT_KEY;
        if (CONSUMER_SIDE.equalsIgnoreCase(url.getParameter(SIDE_KEY))) {
            componentKey = CONSUMER_SIDE;
        }
        Map<Integer, ExecutorService> executors = data.computeIfAbsent(componentKey, k -> new ConcurrentHashMap<>());

        // 2 根据 URL 中的 port 值确定线程池缓存的第二层 key
        Integer portKey = url.getPort();

        // 3 获取或创建线程池
        ExecutorService executor = executors.computeIfAbsent(portKey, k -> createExecutor(url));

        // 4 如果缓存中相应的线程池已经关闭，则创建新的线程池，并替换掉缓存中已关闭的线程池
        if (executor.isShutdown() || executor.isTerminated()) {
            executors.remove(portKey);
            executor = createExecutor(url);
            executors.put(portKey, executor);
        }
        return executor;
    }

    /**
     * 获取线程池
     *
     * @param url
     * @return
     */
    public ExecutorService getExecutor(URL url) {
        // 1 根据 URL 中的 side 参数值确定线程池缓存的第一层 key
        String componentKey = EXECUTOR_SERVICE_COMPONENT_KEY;
        if (CONSUMER_SIDE.equalsIgnoreCase(url.getParameter(SIDE_KEY))) {
            componentKey = CONSUMER_SIDE;
        }

        // 2 从缓存中获取线程池
        Map<Integer, ExecutorService> executors = data.get(componentKey);

        /**
         * It's guaranteed that this method is called after {@link #createExecutorIfAbsent(URL)}, so data should already
         * have Executor instances generated and stored.
         */
        if (executors == null) {
            logger.warn("No available executors, this is not expected, framework should call createExecutorIfAbsent first " +
                    "before coming to here.");
            return null;
        }

        Integer portKey = url.getPort();
        ExecutorService executor = executors.get(portKey);
        if (executor != null) {
            if (executor.isShutdown() || executor.isTerminated()) {
                executors.remove(portKey);
                executor = createExecutor(url);
                executors.put(portKey, executor);
            }
        }
        return executor;
    }


    /**
     * 根据 URL 创建线程池
     *
     * @param url
     * @return
     */
    private ExecutorService createExecutor(URL url) {
        // 通过 Dubbo SPI 查找 ThreadPool 接口的扩展实现，并调用 getExecutor() 方法创建线程池。默认使用 FixedThreadPool 扩展实现。
        return (ExecutorService) ExtensionLoader.getExtensionLoader(ThreadPool.class).getAdaptiveExtension().getExecutor(url);
    }

    @Override
    public void updateThreadpool(URL url, ExecutorService executor) {
        try {
            if (url.hasParameter(THREADS_KEY)
                    && executor instanceof ThreadPoolExecutor && !executor.isShutdown()) {
                ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
                int threads = url.getParameter(THREADS_KEY, 0);
                int max = threadPoolExecutor.getMaximumPoolSize();
                int core = threadPoolExecutor.getCorePoolSize();
                if (threads > 0 && (threads != max || threads != core)) {
                    if (threads < core) {
                        threadPoolExecutor.setCorePoolSize(threads);
                        if (core == max) {
                            threadPoolExecutor.setMaximumPoolSize(threads);
                        }
                    } else {
                        threadPoolExecutor.setMaximumPoolSize(threads);
                        if (core == max) {
                            threadPoolExecutor.setCorePoolSize(threads);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    public ScheduledExecutorService nextScheduledExecutor() {
        return scheduledExecutors.pollItem();
    }

    @Override
    public ScheduledExecutorService getServiceExporterExecutor() {
        return serviceExporterExecutor;
    }

    @Override
    public ExecutorService getSharedExecutor() {
        return SHARED_EXECUTOR;
    }


}
