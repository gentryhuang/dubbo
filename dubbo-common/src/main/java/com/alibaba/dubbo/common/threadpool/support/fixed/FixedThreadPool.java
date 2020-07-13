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
package com.alibaba.dubbo.common.threadpool.support.fixed;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.common.threadpool.ThreadPool;
import com.alibaba.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 实现ThreadPool 接口，固定大小线程池，启动时建立线程，不关闭，一致持有
 * <p>
 * Creates a thread pool that reuses a fixed number of threads
 *
 * @see java.util.concurrent.Executors#newFixedThreadPool(int)
 */
public class FixedThreadPool implements ThreadPool {

    @Override
    public Executor getExecutor(URL url) {
        // 线程名
        String name = url.getParameter(Constants.THREAD_NAME_KEY, Constants.DEFAULT_THREAD_NAME);
        // 线程数，默认200
        int threads = url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS);
        // 队列数，默认为0
        int queues = url.getParameter(Constants.QUEUES_KEY, Constants.DEFAULT_QUEUES);

        // 创建线程池执行器
        return new ThreadPoolExecutor(
                /* 核心线程数*/
                threads,
                /* 最大线程数 */
                threads,
                /*  线程存活时长空闲存活时间*/
                0,
                /* 空闲存活时间单位 */
                TimeUnit.MILLISECONDS,
                /* 阻塞队列,根据配置的队列数，选择对应的队列
                 * 1 queues == 0    -  SynchronousQueue
                 * 2 queues < 0     -  LinkedBlockingQueue
                 * 3 queues > 0     -  带队列数的LinkedBlockingQueue
                 */
                queues == 0 ? new SynchronousQueue<Runnable>() : (queues < 0 ? new LinkedBlockingQueue<Runnable>() : new LinkedBlockingQueue<Runnable>(queues)),
                /* 线程工厂 */
                new NamedInternalThreadFactory(name, true),
                /* 拒绝策略 */
                new AbortPolicyWithReport(name, url)
        );
    }

    /**
     * 配置说明【目前只有服务提供者使用】
     *
     *<dubbo:service interface="com.alibaba.dubbo.demo.DemoService" ref="demoService">
     *
     *     <dubbo:parameter key="threadname" value="dubbo-thread" />
     *     <dubbo:parameter key="threads" value="100" />
     *     <dubbo:parameter key="queues" value="10" />
     *
     * </dubbo:service>
     *
     */

}
