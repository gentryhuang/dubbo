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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.FORKS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_FORKS;

/**
 * NOTICE! This implementation does not work well with async call.
 * <p>
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * 执行多个 Invoker RPC 调用的线程池
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            // 1 检查候选 Invoker 集合是否为空
            checkInvokers(invokers, invocation);
            // 保存选择的 Invoker
            final List<Invoker<T>> selected;
            // 获取 forks 配置项，即并行数，默认为 2
            final int forks = getUrl().getParameter(FORKS_KEY, DEFAULT_FORKS);
            // 获取 timeout 配置项，即超时时间，默认为 1000 毫秒
            final int timeout = getUrl().getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);

            // 2 最大并行数 <= 0 或者 >= Invoker 数，则选择所有的 Invoker
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;

                // 3 根据并行数，选择此次并发调用的 Invoker
            } else {
                selected = new ArrayList<>(forks);

                // 循环并行数，每次循环都要尝试选择一个 Invoker
                // 注意：可能最终得到的 Invoker 列表大小小于并发数
                while (selected.size() < forks) {
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    // 避免重复选择
                    if (!selected.contains(invoker)) {
                        //Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }

            // 4 将选中的 Invoker 列表设置到上下文中
            RpcContext.getContext().setInvokers((List) selected);

            // 记录调用失败数
            final AtomicInteger count = new AtomicInteger();
            // 记录请求的结果
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<>();

            // 5 遍历 selected ，将每个 Invoker 的 RPC 调用提交到线程池，并把结果放入到阻塞队列中
            for (final Invoker<T> invoker : selected) {
                executor.execute(() -> {
                    try {
                        // RPC 调用
                        Result result = invoker.invoke(invocation);
                        // 把调用结果放入到阻塞队列中
                        ref.offer(result);

                        // 调用失败
                    } catch (Throwable e) {
                        // 记录调用失败数
                        int value = count.incrementAndGet();

                        // 如果选择的 Invoker 全部调用失败，则把最后一次调用异常加入到阻塞队列
                        // 确保了只有在全部服务提供者都失败才会抛出异常，即阻塞队列取出的是异常对象
                        if (value >= selected.size()) {
                            ref.offer(e);
                        }
                    }
                });
            }

            try {
                // 6 当前线程会阻塞等待任意一个调用结果，如果选择的 Invoker 全部调用失败，则会获取到一个异常结果。
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getContext().clearAttachments();
        }
    }
}
