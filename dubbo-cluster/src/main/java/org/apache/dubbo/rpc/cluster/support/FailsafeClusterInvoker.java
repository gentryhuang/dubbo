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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * When invoke fails, log the error message and ignore this error by returning an empty Result.
 * Usually used to write audit logs and other operations
 *
 * <a href="http://en.wikipedia.org/wiki/Fail-safe">Fail-safe</a>
 */
public class FailsafeClusterInvoker<T> extends AbstractClusterInvoker<T> {
    private static final Logger logger = LoggerFactory.getLogger(FailsafeClusterInvoker.class);

    public FailsafeClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            // 1 检测候选 Invoker 列表是否为空
            checkInvokers(invokers, invocation);

            // 2 使用 LoadBalance 选择 Invoker 对象
            Invoker<T> invoker = select(loadbalance, invocation, invokers, null);

            // 3 RPC 调用
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            logger.error("Failsafe ignore exception: " + e.getMessage(), e);

            // 4 调用异常，直接返回一个空结果，不会抛出异常
            return AsyncRpcResult.newDefaultAsyncResult(null, null, invocation); // ignore
        }
    }
}
