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
package com.alibaba.dubbo.remoting.transport.dispatcher.all;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.ExecutionException;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelEventRunnable;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.ChannelState;
import com.alibaba.dubbo.remoting.transport.dispatcher.WrappedChannelHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 实现WrappedChannelHandler抽象类 【WrappedChannelHandler实现了ChannelHandlerDelegate抽象类】
 * 1 将所有网络事件和消息交给线程池
 * 2 覆写了 WrappedChannelHandler 中除了 sent() 方法之外的其它方法，执行底层的ChannelHander的逻辑都放在了线程池中
 * 3 注意，AllChannelHandler 并没有覆写 sent 方法，发送消息是直接在当前线程调用 sent() 方法完成的。
 *
 */
public class AllChannelHandler extends WrappedChannelHandler {

    public AllChannelHandler(ChannelHandler handler, URL url) {
        super(handler, url);
    }

    /**
     * 处理连接事件
     *
     * @param channel
     * @throws RemotingException
     */
    @Override
    public void connected(Channel channel) throws RemotingException {
        // 获取线程池
        ExecutorService cexecutor = getExecutorService();
        try {
            // 将CONNECTED 事件的处理封装成ChannelEventRunnable提交到线程池中执行
            cexecutor.execute(new ChannelEventRunnable(channel, handler, ChannelState.CONNECTED));
        } catch (Throwable t) {
            throw new ExecutionException("connect event", channel, getClass() + " error when process connected event .", t);
        }
    }

    /**
     * 处理断开连接事件
     *
     * @param channel
     * @throws RemotingException
     */
    @Override
    public void disconnected(Channel channel) throws RemotingException {
        // 获取线程池
        ExecutorService cexecutor = getExecutorService();
        try {
            // 创建ChannelEventRunnable对象，用于将断开连接事件任务派发到线程池执行
            cexecutor.execute(new ChannelEventRunnable(channel, handler, ChannelState.DISCONNECTED));
        } catch (Throwable t) {
            throw new ExecutionException("disconnect event", channel, getClass() + " error when process disconnected event .", t);
        }
    }

    /**
     * 派发策略 - all ： 所有消息都派发到线程池。
     * <p>
     * 接收请求和响应消息，注意这里的message 可能是 Request也可能是 Response。
     * 具体流程是：消息先由 IO 线程（Netty 中的EventLoopGroup ）从二进制流中解码出来，然后才会执行到该方法，该方法会把请求提交给线程池处理，处理完后调用send 方法用于向对端写回结果。
     *
     * @param channel
     * @param message
     * @throws RemotingException
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {

        ExecutorService cexecutor = getExecutorService();
        try {
            // 将请求和响应消息派发到线程池中处理，ChannelEventRunnable对象作为任务载体
            cexecutor.execute(new ChannelEventRunnable(channel, handler, ChannelState.RECEIVED, message));
        } catch (Throwable t) {
            //TODO A temporary solution to the problem that the exception information can not be sent to the opposite end after the thread pool is full. Need a refactoring
            //fix The thread pool is full, refuses to call, does not return, and causes the consumer to wait for time out

            // 如果是请求消息，并且出现了线程池满了的异常
            if (message instanceof Request && t instanceof RejectedExecutionException) {
                Request request = (Request) message;

                // 如果通信方式为双向通信，将错误信息封装到Response 中，并返回给服务消费方。防止消费端等待超时
                if (request.isTwoWay()) {
                    String msg = "Server side(" + url.getIp() + "," + url.getPort() + ") threadpool is exhausted ,detail msg:" + t.getMessage();
                    Response response = new Response(request.getId(), request.getVersion());
                    response.setStatus(Response.SERVER_THREADPOOL_EXHAUSTED_ERROR);
                    response.setErrorMessage(msg);

                    // 返回包含错误信息的 Response 对象
                    channel.send(response);
                    return;
                }
            }
            throw new ExecutionException(message, channel, getClass() + " error when process received event .", t);
        }
    }

    /**
     * 处理异常信息
     *
     * @param channel
     * @param exception
     * @throws RemotingException
     */
    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        ExecutorService cexecutor = getExecutorService();
        try {
            cexecutor.execute(new ChannelEventRunnable(channel, handler, ChannelState.CAUGHT, exception));
        } catch (Throwable t) {
            throw new ExecutionException("caught event", channel, getClass() + " error when process caught event .", t);
        }
    }
}
