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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 如果失败，返回一个空结果给调用者，并记录失败请求并安排定期重试【注意，重试是不会再给调用者结果了】,对于通知服务特别有用。
 *
 * <a href="http://en.wikipedia.org/wiki/Failback">Failback</a>
 */
public class FailbackClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailbackClusterInvoker.class);

    /**
     * 重试频率 5秒
     */
    private static final long RETRY_FAILED_PERIOD = 5 * 1000;

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link com.alibaba.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link com.alibaba.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     * <p>
     * 任务定时处理线程池
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2, new NamedInternalThreadFactory("failback-cluster-timer", true));

    /**
     * 调用失败信息集合
     * key: 调用信息
     * value: Invoker
     */
    private final ConcurrentMap<Invocation, AbstractClusterInvoker<?>> failed = new ConcurrentHashMap<Invocation, AbstractClusterInvoker<?>>();

    /**
     * 重试任务 Future
     */
    private volatile ScheduledFuture<?> retryFuture;

    /**
     * 构造方法
     *
     * @param directory
     */
    public FailbackClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    /**
     * Rpc调用失败，则调用该方法，把调用失败信息加入到调用失败集合
     *
     * @param invocation
     * @param router 命名不应该是router,而是invoker
     */
    private void addFailed(Invocation invocation, AbstractClusterInvoker<?> router) {

        // 创建定时重试任务
        if (retryFuture == null) {
            synchronized (this) {
                if (retryFuture == null) {

                    // 每隔5秒执行一次任务
                    retryFuture = scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {

                        @Override
                        public void run() {
                            try {
                                // 重试失败的调用
                                retryFailed();
                            } catch (Throwable t) { // Defensive fault tolerance
                                logger.error("Unexpected error occur at collect statistic", t);
                            }
                        }
                    }, RETRY_FAILED_PERIOD, RETRY_FAILED_PERIOD, TimeUnit.MILLISECONDS);
                }
            }
        }

        // 添加到失败集合
        failed.put(invocation, router);
    }

    /**
     * 重试调用失败的任务
     */
    void retryFailed() {

        // 没有要重试的，则直接返回
        if (failed.size() == 0) {
            return;
        }

        // 循环失败调用集合，逐个重试调用
        for (Map.Entry<Invocation, AbstractClusterInvoker<?>> entry : new HashMap<Invocation, AbstractClusterInvoker<?>>(failed).entrySet()) {

            // 调用信息
            Invocation invocation = entry.getKey();
            // Invoker
            Invoker<?> invoker = entry.getValue();

            try {

                // 执行调用
                invoker.invoke(invocation);

                // 调用成功后，移除当前失败任务
                failed.remove(invocation);

            } catch (Throwable e) {
                logger.error("Failed retry to invoke method " + invocation.getMethodName() + ", waiting again.", e);
            }
        }
    }

    /**
     * 执行调用
     *
     * @param invocation  调用信息
     * @param invokers    候选Invoker列表
     * @param loadbalance 负载均衡器
     * @return
     * @throws RpcException
     */
    @Override
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            // 检查候选Invoker列表不能为空
            checkInvokers(invokers, invocation);

            // 从候选Invoker集合中选择一个Invoker 【粘滞Invoker不能用，会使用负载均衡器】
            Invoker<T> invoker = select(loadbalance, invocation, invokers, null);

            // 执行 RPC 调用
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            logger.error("Failback to invoke method " + invocation.getMethodName() + ", wait for retry in background. Ignored exception: " + e.getMessage() + ", ", e);

            // 调用失败，就加入到失败集合中，为了定时重试
            addFailed(invocation, this);

            // 返回一个空的 Result 对象
            return new RpcResult();
        }
    }

}
