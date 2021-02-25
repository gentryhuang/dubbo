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
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;

import java.util.HashMap;
import java.util.Map;

/**
 * ContextInvokerFilter，服务提供者的ContextFilter实现类
 */
@Activate(group = Constants.PROVIDER, order = -10000)
public class ContextFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        /**
         * 创建新的 attachments 集合，清理公用的隐式参数。公用的隐式参数，设置的地方如下：
         *
         * @see RpcInvocation#RpcInvocation(com.alibaba.dubbo.rpc.Invocation, com.alibaba.dubbo.rpc.Invoker)
         */
        Map<String, String> attachments = invocation.getAttachments();
        if (attachments != null) {
            attachments = new HashMap<String, String>(attachments);
            // 清理 path
            attachments.remove(Constants.PATH_KEY);
            // 清理 group
            attachments.remove(Constants.GROUP_KEY);
            // 清理 version
            attachments.remove(Constants.VERSION_KEY);
            // 清理 dubbo
            attachments.remove(Constants.DUBBO_VERSION_KEY);
            // 清理 token
            attachments.remove(Constants.TOKEN_KEY);
            // 清理 timeout
            attachments.remove(Constants.TIMEOUT_KEY);
            // 清除异步属性，防止异步属性传到过滤器下一个环节
            attachments.remove(Constants.ASYNC_KEY);
        }

        // 这里和 ConsumerContextFilter 不同，没有设置 remoteAddress 的值，做为服务端的过滤器，在收到请求的时候就已经设置了 remoteAddress 的值
        // @see com.alibaba.dubbo.remoting.exchange.support.ExchangeHandlerAdapter.reply
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
//                .setAttachments(attachments)  // merged from dubbox
                .setLocalAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort());

        // mreged from dubbox
        // we may already added some attachments into RpcContext before this filter (e.g. in rest protocol)
        if (attachments != null) {
            if (RpcContext.getContext().getAttachments() != null) {
                RpcContext.getContext().getAttachments().putAll(attachments);
            } else {
                RpcContext.getContext().setAttachments(attachments);
            }
        }

        // 设置 RpcInvocation 对象的 'invoker' 属性
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }

        try {
            // 调用过滤器链的下一个节点
            RpcResult result = (RpcResult) invoker.invoke(invocation);
            // 将 SERVER_LOCAL 这个 RpcContext 中的附加信息添加到 RpcResult 的 attachments 字段中，返回给 Consumer。
            result.addAttachments(RpcContext.getServerContext().getAttachments());
            return result;
        } finally {

            // 清除上下文信息，当前线程处理下一个调用的时候，会创建新的 RpcContext
            RpcContext.removeContext();
            RpcContext.getServerContext().clearAttachments();
        }
    }
}
