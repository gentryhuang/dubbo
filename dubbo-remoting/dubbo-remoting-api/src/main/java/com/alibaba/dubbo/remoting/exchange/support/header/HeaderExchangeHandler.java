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
package com.alibaba.dubbo.remoting.exchange.support.header;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.ExecutionException;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;
import com.alibaba.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import com.alibaba.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.net.InetSocketAddress;

/**
 * 实现 ChannelHandlerDelegate 接口，基于消息头部( Header )的信息交换处理器实现类
 */
public class HeaderExchangeHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(HeaderExchangeHandler.class);

    public static String KEY_READ_TIMESTAMP = HeartbeatHandler.KEY_READ_TIMESTAMP;

    public static String KEY_WRITE_TIMESTAMP = HeartbeatHandler.KEY_WRITE_TIMESTAMP;

    private final ExchangeHandler handler;

    public HeaderExchangeHandler(ExchangeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.handler = handler;
    }

    /**
     * 处理响应 - 客户端收到服务端的响应
     *
     * @param channel
     * @param response
     * @throws RemotingException
     */
    static void handleResponse(Channel channel, Response response) throws RemotingException {
        // 非心跳事件响应，就使用DefaultFuture#received(channel, response) 方法，唤醒等待请求结果的线程。
        if (response != null && !response.isHeartbeat()) {
            DefaultFuture.received(channel, response);
        }
    }

    private static boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(url.getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    /**
     * 处理事件请求
     *
     * @param channel 通道
     * @param req     请求
     * @throws RemotingException
     */
    void handlerEvent(Channel channel, Request req) throws RemotingException {
        // 如果是只读请求
        if (req.getData() != null && req.getData().equals(Request.READONLY_EVENT)) {
            // 服务端收到 READONLY_EVENT 事件请求，记录到通道，后续不再向该服务器发送新的请求
            channel.setAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY, Boolean.TRUE);
        }
    }

    /**
     * 处理普通请求 - 需要响应
     *
     * @param channel
     * @param req
     * @return
     * @throws RemotingException
     */
    Response handleRequest(ExchangeChannel channel, Request req) throws RemotingException {
        // 创建响应对象
        Response res = new Response(req.getId(), req.getVersion());
        // 如果是异常请求，则返回  Response.BAD_REQUEST 响应
        if (req.isBroken()) {
            Object data = req.getData();
            // 请求数据，转成 msg
            String msg;
            if (data == null) {
                msg = null;
            } else if (data instanceof Throwable) {
                msg = StringUtils.toString((Throwable) data);
            } else {
                msg = data.toString();
            }
            res.setErrorMessage("Fail to decode request due to: " + msg);

            // 设置 BAD_REQUEST 状态
            res.setStatus(Response.BAD_REQUEST);

            return res;
        }

        // 使用 ExchangeHandler 处理，并返回响应

        // 获取 data 字段值，也就是 RpcInvocation 对象
        Object msg = req.getData();
        try {
            /** 处理请求
             * @see ExchangeHandlerAdapter#reply(com.alibaba.dubbo.remoting.exchange.ExchangeChannel, java.lang.Object)
             * 在DubboProtocol中基于 ExchangeHandlerAdapter实现自己的处理器，处理请求，返回结果，{@link com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler}
             */
            Object result = handler.reply(channel, msg);

            // 封装请求状态和结果
            res.setStatus(Response.OK);
            res.setResult(result);
        } catch (Throwable e) {

            // 若调用过程出现异常，则设置 SERVICE_ERROR，表示服务端异常
            res.setStatus(Response.SERVICE_ERROR);
            res.setErrorMessage(StringUtils.toString(e));
        }
        // 返回响应
        return res;
    }

    /**    todo  从handler {@link com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers#wrapInternal(ChannelHandler, URL)}链来看，无论是请求还是响应都会按照handler链来处理一遍。那么在HeartbeatHandler中已经进行了lastWrite和lastRead的设置，为什么还要在HeaderExchangeHandler中再处理一遍  --------*/


    /**
     * 连接完成时：设置lastRead和lastWrite
     *
     * @param channel channel.
     * @throws RemotingException
     */
    @Override
    public void connected(Channel channel) throws RemotingException {
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.connected(exchangeChannel);
        } finally {
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    /**
     * 连接断开时：也设置lastRead和lastWrite todo HeartbeatHandler 中是清空，这里为什么还要设置
     *
     * @param channel channel.
     * @throws RemotingException
     */
    @Override
    public void disconnected(Channel channel) throws RemotingException {
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.disconnected(exchangeChannel);
        } finally {
            DefaultFuture.closeChannel(channel);
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    /**
     * 发送消息时：设置lastWrite
     *
     * @param channel channel.
     * @param message message.
     * @throws RemotingException
     */
    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        Throwable exception = null;
        try {
            channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
            ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
            try {
                handler.sent(exchangeChannel, message);
            } finally {
                HeaderExchangeChannel.removeChannelIfDisconnected(channel);
            }
        } catch (Throwable t) {
            exception = t;
        }
        if (message instanceof Request) {
            Request request = (Request) message;
            DefaultFuture.sent(channel, request);
        }
        if (exception != null) {
            if (exception instanceof RuntimeException) {
                throw (RuntimeException) exception;
            } else if (exception instanceof RemotingException) {
                throw (RemotingException) exception;
            } else {
                throw new RemotingException(channel.getLocalAddress(), channel.getRemoteAddress(),
                        exception.getMessage(), exception);
            }
        }
    }

    /**
     * 接收消息
     * <p>
     * 设置lastRead
     *
     * @param channel channel 通道
     * @param message message 消息
     * @throws RemotingException
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 设置最后的读时间
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        // 创建消息交换通道
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {

            // 如果是请求 【服务端收到客户端的请求】
            if (message instanceof Request) {
                // handle request.
                Request request = (Request) message;
                // 处理事件请求
                if (request.isEvent()) {
                    handlerEvent(channel, request);

                    // 处理普通请求
                } else {
                    // 需要响应，要将响应写回请求方
                    if (request.isTwoWay()) {
                        Response response = handleRequest(exchangeChannel, request);

                        // 将调用结果返回给服务消费端  todo 问题： 1 channel 类型 ？
                        channel.send(response);

                        // 不需要返回调用结果， 提交给 handler 处理
                    } else {
                        handler.received(exchangeChannel, request.getData());
                    }
                }

                // 处理响应 【客户端收到服务端的响应】
            } else if (message instanceof Response) {
                handleResponse(channel, (Response) message);
                // 处理String
            } else if (message instanceof String) {

                // 客户端侧 不支持String
                if (isClientSide(channel)) {
                    Exception e = new Exception("Dubbo client can not supported string message: " + message + " in channel: " + channel + ", url: " + channel.getUrl());
                    logger.error(e.getMessage(), e);

                    // 服务端侧，目前仅有 telnet 命令的情况
                } else {
                    // 调用handler 的 telnet方法，处理telnet命令，并将执行命令的结果发送可客户端。【注意：ExchangeHandler实现了TelnetHandler接口】
                    String echo = handler.telnet(channel, (String) message);
                    if (echo != null && echo.length() > 0) {
                        channel.send(echo);
                    }
                }

                //其他情况， 提交给 handLer 处理
            } else {
                handler.received(exchangeChannel, message);
            }
        } finally {
            // 如果已经断开，则移除 ExchangeChannel 对象
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    /**
     * 发生异常
     *
     * @param channel   channel.
     * @param exception exception.
     * @throws RemotingException
     */
    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {

        // 当发生 ExecutionException 异常，返回异常响应( Response )

        if (exception instanceof ExecutionException) {
            ExecutionException e = (ExecutionException) exception;
            Object msg = e.getRequest();


            // 请求消息
            if (msg instanceof Request) {
                Request req = (Request) msg;

                // 需要响应，并且非心跳时间
                if (req.isTwoWay() && !req.isHeartbeat()) {
                    Response res = new Response(req.getId(), req.getVersion());
                    res.setStatus(Response.SERVER_ERROR);
                    res.setErrorMessage(StringUtils.toString(e));
                    channel.send(res);
                    return;
                }
            }
        }

        // 创建 ExchangeChannel 对象
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            // 提交给 `handler` 处理
            handler.caught(exchangeChannel, exception);
        } finally {
            // 若已断开， 移除 ExchangeChannel 对象
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public ChannelHandler getHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }
}
