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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;

/**
 * ConsumerContextInvokerFilter，服务消费者的ContextFilter实现类
 * 说明：
 * ConsumerContextFilter 会和 ContextFilter 配合使用，因为在微服务环境中，有很多链式调用。收到请求时，当前节点可以被看作一个服务提供者，由ContextFilter设置上下文。
 * 当发起请求到其他服务，当前服务变成一个消费者，由ConsumerContextFilter设置上下文。
 */
@Activate(group = Constants.CONSUMER, order = -10000)
public class ConsumerContextFilter implements Filter {
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        RpcContext.getContext()
                // 记录Invoker
                .setInvoker(invoker)
                // 记录服务调用参数
                .setInvocation(invocation)
                // 本地地址
                .setLocalAddress(NetUtils.getLocalHost(), 0)
                // 远端地址
                .setRemoteAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort());

        // 设置 RpcInvocation 对象的 invoker 属性
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }

        try {
            RpcResult result = (RpcResult) invoker.invoke(invocation);
            RpcContext.getServerContext().setAttachments(result.getAttachments());
            return result;
        } finally {
            /**
             * 清理隐式参数集合
             * 注意：每次服务调用完成，RpcContext设置的隐式参数都会被清理
             */
            RpcContext.getContext().clearAttachments();
        }
    }

}
