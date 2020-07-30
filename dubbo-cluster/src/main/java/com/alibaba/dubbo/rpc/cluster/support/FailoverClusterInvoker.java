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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 * 失败自动切换，当出现失败，重试其它服务器。通常用于读操作。
 * 注意：
 * 重试会带来延迟
 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailoverClusterInvoker.class);

    public FailoverClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    /**
     * 循环，查找一个Invoker 对象，进行调用，直到成功
     *
     * @param invocation  调用信息
     * @param invokers    获选Invoker 集合
     * @param loadbalance 负载均衡器
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {

        // 校验候选Invoker 集合是否为空
        List<Invoker<T>> copyinvokers = invokers;
        checkInvokers(copyinvokers, invocation);

        // 获取配置的重试次数，默认是 2+1 次
        int len = getUrl().getMethodParameter(invocation.getMethodName(), Constants.RETRIES_KEY, Constants.DEFAULT_RETRIES) + 1;
        if (len <= 0) {
            len = 1;
        }

        // 保存最后一次调用异常【如果调用出现异常的情况】
        RpcException le = null;

        // 保存已经调用过的Invoker集合
        List<Invoker<T>> invoked = new ArrayList<Invoker<T>>(copyinvokers.size());

        // 负载均衡器选择出来的Invoker的网络地址
        Set<String> providers = new HashSet<String>(len);

        // 如果出现调用失败，则重试其他服务。[失败重试机制的核心逻辑]
        for (int i = 0; i < len; i++) {
            /**
             * 重试时进行重新选择 Invoker列表，避免重试时 Invoker列表已发生改变。需要注意的是：如果列表发生了改变，那么invoked 判断会失效
             */
            if (i > 0) {
                // 校验Invoker是否销毁
                checkWhetherDestroyed();
                // 通过 Directory 获取路由过滤的服务提供者Invokers集合
                copyinvokers = list(invocation);
                // 再次检验Invoker 是否为空
                checkInvokers(copyinvokers, invocation);
            }

            // 从候选Invoker集合中选择一个Invoker 【粘滞Invoker不能用，会使用负载均衡器】
            Invoker<T> invoker = select(loadbalance, invocation, copyinvokers, invoked);

            // 保存选择出来的Invoker
            invoked.add(invoker);

            // 保存已经选择出来的Invoker 列表到 上下文
            RpcContext.getContext().setInvokers((List) invoked);

            try {
                // RPC 调用
                Result result = invoker.invoke(invocation);

                // 重试过程中，打印最后一次调用的异常信息
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn("Although retry the method " + invocation.getMethodName()
                            + " in the service " + getInterface().getName()
                            + " was successful by the provider " + invoker.getUrl().getAddress()
                            + ", but there have been failed providers " + providers
                            + " (" + providers.size() + "/" + copyinvokers.size()
                            + ") from the registry " + directory.getUrl().getAddress()
                            + " on the consumer " + NetUtils.getLocalHost()
                            + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                            + le.getMessage(), le);
                }
                return result;

            } catch (RpcException e) {
                // 如果是业务性质的异常，不再重试，直接抛出
                if (e.isBiz()) {
                    throw e;
                }
                le = e;

                // 其它异常统一封装成 RpcException
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                // 保存选中的Invoker的地址
                providers.add(invoker.getUrl().getAddress());
            }
        }

        // 最大重试次数用完还是调用失败的话，就抛出RpcException异常，输出最后一次异常信息
        throw new RpcException(le != null ? le.getCode() : 0, "Failed to invoke the method "
                + invocation.getMethodName() + " in the service " + getInterface().getName()
                + ". Tried " + len + " times of the providers " + providers
                + " (" + providers.size() + "/" + copyinvokers.size()
                + ") from the registry " + directory.getUrl().getAddress()
                + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                + Version.getVersion() + ". Last error is: "
                + (le != null ? le.getMessage() : ""), le != null && le.getCause() != null ? le.getCause() : le);
    }

}
