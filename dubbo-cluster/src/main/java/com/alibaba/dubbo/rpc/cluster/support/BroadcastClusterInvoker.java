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
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * BroadcastClusterInvoker，BroadcastClusterInvoker 实现类
 * 说明：
 * 广播调用所有提供者，逐个调用，任意一台报错则报错。通常用于通知所有提供者更新缓存或日志等本地资源信息。
 */
public class BroadcastClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(BroadcastClusterInvoker.class);

    /**
     * 构造方法
     *
     * @param directory
     */
    public BroadcastClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {

        // 检查 Invoker 列表是否为空，为空会抛出异常
        checkInvokers(invokers, invocation);

        // 保存候选Invoker列表到Dubbo 上下文
        RpcContext.getContext().setInvokers((List) invokers);

        // 保存最后一次调用的异常
        RpcException exception = null;

        // 保存最后一次调用结果
        Result result = null;

        // 循环调用所有候选的Invoker
        for (Invoker<T> invoker : invokers) {
            try {

                // 执行Rpc调用
                result = invoker.invoke(invocation);

            } catch (RpcException e) {
                exception = e;
                logger.warn(e.getMessage(), e);
            } catch (Throwable e) {

                exception = new RpcException(e.getMessage(), e);
                logger.warn(e.getMessage(), e);
            }
        }

        // 如果存在一个调用异常，就抛出该异常
        if (exception != null) {
            throw exception;
        }
        return result;
    }

}
