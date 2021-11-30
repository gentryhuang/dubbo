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

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_RETRIES;
import static org.apache.dubbo.common.constants.CommonConstants.RETRIES_KEY;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailoverClusterInvoker.class);

    public FailoverClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        List<Invoker<T>> copyInvokers = invokers;
        // 1 检查候选 Invoker 列表是否为空
        checkInvokers(copyInvokers, invocation);

        // 2 获取调用方法名
        String methodName = RpcUtils.getMethodName(invocation);

        // 3 获取配置的重试次数，默认重试 2 次，总共执行 3 次
        int len = getUrl().getMethodParameter(methodName, RETRIES_KEY, DEFAULT_RETRIES) + 1;
        if (len <= 0) {
            len = 1;
        }

        // 4 准备调用记录属性
        // 记录最后一次调用异常（如果有的情况下）
        RpcException le = null;
        // 记录已经调用过的 Invoker
        List<Invoker<T>> invoked = new ArrayList<Invoker<T>>(copyInvokers.size());
        // 记录负载均衡选出来的 Invoker 的网络地址
        Set<String> providers = new HashSet<String>(len);

        // 5 如果出现调用失败，则重试其他服务。这是该集群容错机制的核心。
        for (int i = 0; i < len; i++) {
            // 第一次传进来的 invokers 已经check过了，第二次则是重试，需要重新获取最新的服务列表
            if (i > 0) {
                // 检查当前 ClusterInvoker 是否可用
                checkWhetherDestroyed();
                // 重新从服务目录中拉取 Invoker 列表
                copyInvokers = list(invocation);
                // 检查 copyInvokers ，防止服务目录中的 Invoker 列表为空
                checkInvokers(copyInvokers, invocation);
            }

            // 使用 LoadBalance 选择 Invoker 对象，这里传入 invoked 集合
            Invoker<T> invoker = select(loadbalance, invocation, copyInvokers, invoked);
            // 记录此次尝试调用的 Invoker ，之后非兜底情况会过滤掉该 Invoker
            invoked.add(invoker);

            // 保存已选过的 Invoker 到上下文
            RpcContext.getContext().setInvokers((List) invoked);
            try {
                // RPC 调用
                Result result = invoker.invoke(invocation);
                // 经过重试之后，成功了。这里会打印最后一次调用的异常信息。
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn("Although retry the method " + methodName
                            + " in the service " + getInterface().getName()
                            + " was successful by the provider " + invoker.getUrl().getAddress()
                            + ", but there have been failed providers " + providers
                            + " (" + providers.size() + "/" + copyInvokers.size()
                            + ") from the registry " + directory.getUrl().getAddress()
                            + " on the consumer " + NetUtils.getLocalHost()
                            + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                            + le.getMessage(), le);
                }
                return result;
            } catch (RpcException e) {
                // 如果是业务性质的异常，则不再重试，直接抛出异常
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;

                // 其他异常同一封装成 RpcException，表示此次尝试失败，会进行重试。
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                // 记录尝试过的提供者的地址
                providers.add(invoker.getUrl().getAddress());
            }
        }

        // 达到重试次数上限后仍然调用失败的话，就抛出异常。
        throw new RpcException(le.getCode(), "Failed to invoke the method "
                + methodName + " in the service " + getInterface().getName()
                + ". Tried " + len + " times of the providers " + providers
                + " (" + providers.size() + "/" + copyInvokers.size()
                + ") from the registry " + directory.getUrl().getAddress()
                + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                + Version.getVersion() + ". Last error is: "
                + le.getMessage(), le.getCause() != null ? le.getCause() : le);
    }

}
