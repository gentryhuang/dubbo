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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.remoting.exchange.support.header.HeaderExchangeServer;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    /**
     * 远程通信客户端数组 - 会在服务引用的时候初始化 【类型是：ReferenceCountExchangeClient】
     */
    private final ExchangeClient[] clients;
    /**
     * 使用的 {@link #clients} 的位置
     */
    private final AtomicPositiveInteger index = new AtomicPositiveInteger();
    /**
     * 版本
     */
    private final String version;

    /**
     * 销毁方法中使用的jvm 锁
     */
    private final ReentrantLock destroyLock = new ReentrantLock();

    /**
     * Invoker 集合，从{@link DubboProtocol#invokers} 获取
     */
    private final Set<Invoker<?>> invokers;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(serviceType, url, new String[]{Constants.INTERFACE_KEY, Constants.GROUP_KEY, Constants.TOKEN_KEY, Constants.TIMEOUT_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getParameter(Constants.VERSION_KEY, "0.0.0");
        this.invokers = invokers;
    }

    /**
     * 消费者调用服务，即DubboInvoker 会调用 Client ，向服务提供者发起请求
     *
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        RpcInvocation inv = (RpcInvocation) invocation;
        // 获得方法名
        final String methodName = RpcUtils.getMethodName(invocation);
        // 设置 path（服务名），version 到 attachment 中
        inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());
        inv.setAttachment(Constants.VERSION_KEY, version);

        // 获得 远程通信客户端
        ExchangeClient currentClient;

        // 默认是单一长连接
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            currentClient = clients[index.getAndIncrement() % clients.length];
        }

        // 远程调用
        try {
            // 获得是否异步调用
            boolean isAsync = RpcUtils.isAsync(getUrl(), invocation);
            // 获得是否单向调用
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            // 获得调用超时时间 （毫秒）
            int timeout = getUrl().getMethodParameter(methodName, Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);

            // 单向调用 【可以理解为异步无返回值】
            if (isOneway) {
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                // 注意，调用的是 ExchangeClient#send(invocation, sent) 方法，发送消息，而不是请求
                currentClient.send(inv, isSent);
                // 设置 RpcContext.future = null ，无需 FutureFilter ，异步回调
                RpcContext.getContext().setFuture(null);
                // 返回 空结果
                return new RpcResult();


                // 异步调用【有返回值】
            } else if (isAsync) {

                /**
                 *  调用 ExchangeClient#request(invocation, timeout) 方法，发送请求
                 *
                 *  DefaultFuture是ResponseFuture的实现类，实际上这里返回的就是DefaultFuture实例，而该实例就是HeaderExchangeChannel.request(Object request, int timeout)返回的future实例
                 */
                ResponseFuture future = currentClient.request(inv, timeout);
                /**
                 * 1 调用 RpcContext#setFuture(future) 方法，在 FutureFitler 中，异步回调。
                 * 2 将DefaultFuture 对象封装到 FutureAdapter实例中，并将 FutureAdapter实例设置到RpcContext 中，我们可以在需要的地方取出使用 【在合适的地方调用 get方法】
                 * 3 FutureAdapter 是一个适配器，用于将 Dubbo 中的 ResponseFuture 与 JDK 中的 Future 进行适配，这样当用户线程调用 Future 的 get 方法时，经过 FutureAdapter 适配，最终会调用 ResponseFuture 实现类对象的 get 方法，也就是 DefaultFuture 的 get 方法
                 */
                RpcContext.getContext().setFuture(new FutureAdapter<Object>(future));
                // 返回 空结果
                return new RpcResult();

                // 同步调用
            } else {
                // 设置 RpcContext.future = null，无需FutureFilter ，异步回调
                RpcContext.getContext().setFuture(null);
                /**
                 * 1 调用 ExchangeClient#request(invocation, timeout) 方法，发送请求
                 * 2 用 ResponseFuture#get() 方法，阻塞等待返回结果
                 */
                return (Result) currentClient.request(inv, timeout).get();
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        for (ExchangeClient client : clients) {
            /**
             * 即使Client 处于连接中，但是Server 处于正在关闭中，也算不可用。
             *
             * {@link HeaderExchangeServer#sendChannelReadOnlyEvent()}
             */
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    /**
     * 销毁ExchangeClient
     */
    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        // 如果已经销毁，则忽略
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            // 双重检锁，避免已经关闭
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }

                // 标记关闭
                super.destroy();

                // 从缓存中移除当前Invoker
                if (invokers != null) {
                    invokers.remove(this);
                }

                /**
                 *  循环ExchangeClient，依次进行关闭
                 *  说明：
                 *   在DubboProtocol#destroy()方法中已经关闭客户端，但是DubboInvoker中有ExchangeClient缓存，当DubboInvoker需要进行销毁时，此时也应该关闭客户端连接，
                 *   保证客户端连接确实关闭。
                 */

                for (ExchangeClient client : clients) {
                    try {
                        // 等待时长内关闭 ExchangeClient
                        client.close(ConfigUtils.getServerShutdownTimeout());
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                // 释放锁
                destroyLock.unlock();
            }
        }
    }
}
