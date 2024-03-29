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
package org.apache.dubbo.rpc.cluster.interceptor;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;

@Activate
public class ConsumerContextClusterInterceptor implements ClusterInterceptor, ClusterInterceptor.Listener {

    @Override
    public void before(AbstractClusterInvoker<?> invoker, Invocation invocation) {
        RpcContext context = RpcContext.getContext();
        // 设置当前 Consumer 地址
        context.setInvocation(invocation).setLocalAddress(NetUtils.getLocalHost(), 0);
        // 设置此次调用的 Invoker
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        // 移除 Server Context
        RpcContext.removeServerContext();
    }

    @Override
    public void after(AbstractClusterInvoker<?> clusterInvoker, Invocation invocation) {
        // 移除本地 RpcContext
        RpcContext.removeContext(true);
    }

    /**
     * 实现了 ClusterInterceptor.Listener 接口
     *
     * @param appResponse
     * @param invoker
     * @param invocation
     */
    @Override
    public void onMessage(Result appResponse, AbstractClusterInvoker<?> invoker, Invocation invocation) {
        // 获取响应中的 attachments 并设置到 RpcContext
        RpcContext.getServerContext().setObjectAttachments(appResponse.getObjectAttachments());
    }

    @Override
    public void onError(Throwable t, AbstractClusterInvoker<?> invoker, Invocation invocation) {

    }
}
