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

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * Execute exactly once, which means this policy will throw an exception immediately in case of an invocation error.
 * Usually used for non-idempotent write operations
 *
 * <a href="http://en.wikipedia.org/wiki/Fail-fast">Fail-fast</a>
 * <p>
 * 只发起一次调用，失败立即报错的Invoker，即快速失败的Invoker。适用于幂等操作。
 */
public class FailfastClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * 构造方法
     *
     * @param directory
     */
    public FailfastClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        // 校验候选Invoker 列表不能为空，否则报错
        checkInvokers(invokers, invocation);

        // 从候选Invoker集合中选择一个Invoker 【粘滞Invoker不能用，会使用负载均衡器】
        Invoker<T> invoker = select(loadbalance, invocation, invokers, null);

        try {

            // 执行Rpc 调用
            return invoker.invoke(invocation);

            // 调用失败即直接抛出异常
        } catch (Throwable e) {
            // biz exception.
            if (e instanceof RpcException && ((RpcException) e).isBiz()) {
                throw (RpcException) e;
            }
            throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failfast invoke providers " + invoker.getUrl() + " " + loadbalance.getClass().getSimpleName() + " select from all providers " + invokers + " for service " + getInterface().getName() + " method " + invocation.getMethodName() + " on consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
        }
    }
}
