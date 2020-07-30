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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 * <p>
 * 通过线程池创建多个线程，并发调用多个服务提供者，只要一个成功即返回。
 * 说明：
 * 应用场景是在一些对实时性要求比较高读操作（注意是读操作，并行写操作可能不安全），但是会消耗很多的资源。
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link com.alibaba.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link com.alibaba.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     * <p>
     * 任务线程池 - CachedThreadPool
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(new NamedInternalThreadFactory("forking-cluster-timer", true));

    /**
     * 构造方法
     *
     * @param directory
     */
    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {

            // 校验 候选Invoker列表非空
            checkInvokers(invokers, invocation);

            // 保存选择的Invoker 的集合
            final List<Invoker<T>> selected;

            // 获取配置的并行数，默认为2
            final int forks = getUrl().getParameter(Constants.FORKS_KEY, Constants.DEFAULT_FORKS);

            // 获取配置的调用超时时间，默认 1000 毫秒
            final int timeout = getUrl().getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);

            // 最大并行数 <= 0 或者大于 Invoker 数，则就选择所有的Invoker
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;

                // 根据并行数，选择目标Invoker
            } else {
                selected = new ArrayList<Invoker<T>>();

                /**
                 * 循环并行数，从候选Invoker集合中选择一个Invoker 【粘滞Invoker不能用，会使用负载均衡器】
                 * 注意：
                 *  如果使用负载均衡器进行选择，可能最终得到的Invoker列表大小小于并发数
                 */
                for (int i = 0; i < forks; i++) {
                    // TODO. Add some comment here, refer chinese version for more details.
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);

                    //避免添加相同的Invoker
                    if (!selected.contains(invoker)) {
                        selected.add(invoker);
                    }
                }
            }

            // 将选中的Invoker列表设置到Dubbo 上下文
            RpcContext.getContext().setInvokers((List) selected);

            // 原子异常计算器
            final AtomicInteger count = new AtomicInteger();

            // 创建基于链表的阻塞队列,在这里可以通过它实现线程池执行任务的结果通知，进而判断是否执行成功。todo【需要注意内存益处问题】
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<Object>();

            /**
             * 循环选中的Invoker列表，把Invoker执行Rpc调用任务提交到线程池，并把调用结果放入阻塞队列中。
             */
            for (final Invoker<T> invoker : selected) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {

                            // 执行Rpc 调用
                            Result result = invoker.invoke(invocation);

                            // 把Rpc调用成功的结果放入到阻塞队列中 todo 就目前的逻辑是根据阻塞队列的特性-在超时时间内取队列首位元素，所以这里加入操作是必要的
                            ref.offer(result);

                            // 调用出现异常
                        } catch (Throwable e) {

                            // 记录异常数
                            int value = count.incrementAndGet();

                            // 如果选中的Invoker全部调用失败，则把最后一次调用异常加入到阻塞队列。todo 确保了只有在全部服务提供者都失败才会抛出异常，即阻塞队列取出的是异常对象
                            if (value >= selected.size()) {
                                ref.offer(e);
                            }
                        }
                    }
                });
            }


            try {

                // 从阻塞队列中，在超时时间内，等待队首的元素。如果在超时时间内没有获取到元素，则会失败。
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);

                // 如果阻塞队列中队首元素是异常对象，说明服务全部调用失败了，则抛出异常
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }

                // 正常返回
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
