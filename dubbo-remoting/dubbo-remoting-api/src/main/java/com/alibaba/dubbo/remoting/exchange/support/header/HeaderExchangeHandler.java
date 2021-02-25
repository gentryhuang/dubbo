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

    /**
     * 设置Channel的读时间戳 的 key
     */
    public static String KEY_READ_TIMESTAMP = HeartbeatHandler.KEY_READ_TIMESTAMP;
    /**
     * 设置Channel的写时间戳 的 key
     */
    public static String KEY_WRITE_TIMESTAMP = HeartbeatHandler.KEY_WRITE_TIMESTAMP;

    /**
     * 被装饰的 ChannelHandler,由上层传入
     */
    private final ExchangeHandler handler;

    /**
     * 构造方法
     *
     * @param handler
     */
    public HeaderExchangeHandler(ExchangeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.handler = handler;
    }

    /**
     * 处理响应 - 客户端收到服务端的响应
     *
     * @param channel  底层 Dubbo 通道
     * @param response 响应
     * @throws RemotingException
     */
    static void handleResponse(Channel channel, Response response) throws RemotingException {
        // 只处理非心跳事件响应，调用DefaultFuture#received(channel, response) 方法设置响应结果以及唤醒等待请求结果的线程。
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
     * 处理只读事件请求
     *
     * @param channel 底层 Dubbo 通道
     * @param req     请求
     * @throws RemotingException
     */
    void handlerEvent(Channel channel, Request req) throws RemotingException {
        // 如果是只读请求 'R'
        if (req.getData() != null && req.getData().equals(Request.READONLY_EVENT)) {
            // 客户端收到 READONLY_EVENT 事件请求后记录到通道，后续不再向该服务器发送新的请求
            channel.setAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY, Boolean.TRUE);
        }
    }

    /**
     * 处理普通请求 - 需要响应
     *
     * @param channel 底层 Dubbo 通道
     * @param req
     * @return
     * @throws RemotingException
     */
    Response handleRequest(ExchangeChannel channel, Request req) throws RemotingException {
        // 创建响应对象
        Response res = new Response(req.getId(), req.getVersion());

        // 如果是解码失败的请求，则返回状态为 BAD_REQUEST 的异常结果
        if (req.isBroken()) {
            // 请求数据，转成 msg
            Object data = req.getData();
            String msg;
            if (data == null) {
                msg = null;
            } else if (data instanceof Throwable) {
                msg = StringUtils.toString((Throwable) data);
            } else {
                msg = data.toString();
            }
            res.setErrorMessage("Fail to decode request due to: " + msg);
            res.setStatus(Response.BAD_REQUEST);
            // 返回
            return res;
        }

        // 获取请求数据，这里一般是 RpcInvocation 对象
        Object msg = req.getData();
        try {

            /** 处理请求
             * @see ExchangeHandlerAdapter#reply(com.alibaba.dubbo.remoting.exchange.ExchangeChannel, java.lang.Object)
             * 在DubboProtocol中基于 ExchangeHandlerAdapter实现自己的处理器，处理请求，返回结果，{@link com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol#requestHandler}
             */

            // 交给上层实现的 ExchangeHandler 进行处理
            Object result = handler.reply(channel, msg);

            // 封装请求状态和结果
            res.setStatus(Response.OK);
            res.setResult(result);

            // 上层实现的 ExchangeHandler 处理异常
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
     * 对连接建立的处理
     *
     * @param channel 底层的 Dubbo Channel
     * @throws RemotingException
     */
    @Override
    public void connected(Channel channel) throws RemotingException {
        // 1. 设置读写时间戳
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());

        // 2. 创建 channel 相应的 HeaderExchangeChannel 并将两者绑定.
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            // 3. 通知上层 ExchangeHandler 处理 connect 事件
            handler.connected(exchangeChannel);
        } finally {
            // 4. 解绑 channel 和 HeaderExchangeChannel 的联系
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    /**
     * 对连接断开的处理
     *
     * @param channel 底层的 Dubbo Channel
     * @throws RemotingException
     */
    @Override
    public void disconnected(Channel channel) throws RemotingException {
        // 1. 设置读写时间戳
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());

        // 2. 创建 channel 相应的 HeaderExchangeChannel 并将两者绑定.
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {

            // 3. 通知上层 ExchangeHandler 处理 disconnect
            handler.disconnected(exchangeChannel);
        } finally {

            // 4. 调用 DefaultFuture.closeChannel 方法通知 DefaultFuture 连接断开了，避免连接断开了还在阻塞业务线程。
            // DefaultFuture 接到连接断开通知后会先获取连接对应的请求，再通过请求找到关联的 DefaultFuture，判断该请求是否响应，没有响应就创建一个状态码为 CHANNEL_INACTIVE 的 Response 并设置到结果属性。
            DefaultFuture.closeChannel(channel);

            // 5. 若channel已经断开，则 解绑 channel 与 HeaderExchangeChannel 的联系
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    /**
     * 处理发送的数据
     *
     * @param channel 底层的 Dubbo Channel
     * @param message 可能是请求/响应 消息
     * @throws RemotingException
     */
    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        Throwable exception = null;
        try {
            // 1. 设置写时间
            channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
            // 2. 创建 channel 相应的 HeaderExchangeChannel 并将两者绑定.
            ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
            try {
                // 3. 通知上层 ExchangeHandler 实现的 sent() 方法
                handler.sent(exchangeChannel, message);
            } finally {
                // 4. 若channel已经断开，则 解绑 channel 与 HeaderExchangeChannel 的联系
                HeaderExchangeChannel.removeChannelIfDisconnected(channel);
            }
            // 上层 ExchangeHandler 实现的 sent() 方法 执行异常
        } catch (Throwable t) {
            exception = t;
        }

        // 5. 如果是请求，则调用 DefaultFuture.sent() 方法更新请求的具体发送时间
        if (message instanceof Request) {
            Request request = (Request) message;
            DefaultFuture.sent(channel, request);
        }

        // 6. 如果发送消息出现异常，则进行处理
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
     *
     * @param channel 底层的 Dubbo Channel
     * @param message message 消息
     * @throws RemotingException
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 1. 设置最后的读时间
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        // 2. 创建 channel 相应的 HeaderExchangeChannel 并将两者绑定.
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);

        // 3. 对收到的消息分类
        try {

            // 3.1 处理请求消息
            if (message instanceof Request) {
                Request request = (Request) message;
                // 3.1.1 只读请求
                if (request.isEvent()) {
                    // 在Channel上设置'channel.readonly' 标志，然后往下传即可
                    handlerEvent(channel, request);
                    // 处理普通请求
                } else {
                    // 3.1.2 需要响应，要将响应写回请求方
                    if (request.isTwoWay()) {
                        // 处理请求
                        Response response = handleRequest(exchangeChannel, request);
                        // 将调用结果返回给服务消费端
                        channel.send(response);

                        // 3.1.3 不需要响应，直接交给上层实现的 ExchangeHandler 进行处理
                    } else {
                        handler.received(exchangeChannel, request.getData());
                    }
                }

                // 3.2 处理响应响应消息
            } else if (message instanceof Response) {
                // 将关联的 DefaultFuture 设置为完成状态（或是异常完成状态）
                handleResponse(channel, (Response) message);

                // 3.3 处理String类型的消息，根据当前服务的角色进行分类处理
            } else if (message instanceof String) {

                // 3.3.1 客户端侧 不支持String
                if (isClientSide(channel)) {
                    Exception e = new Exception("Dubbo client can not supported string message: " + message + " in channel: " + channel + ", url: " + channel.getUrl());
                    logger.error(e.getMessage(), e);

                    // 3.3.2 服务端侧，目前仅有 telnet 命令的情况
                } else {
                    // 调用 handler 的 telnet方法，处理telnet命令，并将执行命令的结果发送可客户端。【注意：ExchangeHandler实现了TelnetHandler接口】
                    String echo = handler.telnet(channel, (String) message);
                    if (echo != null && echo.length() > 0) {
                        channel.send(echo);
                    }
                }

                // 3.4 其他情况，直接交给上层实现的 ExchangeHandler 进行处理
            } else {
                handler.received(exchangeChannel, message);
            }
        } finally {
            // 4. 若channel已经断开，则 解绑 channel 与 HeaderExchangeChannel 的联系
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    /**
     * 捕获到异常
     *
     * @param channel   底层的 Dubbo Channel
     * @param exception exception.
     * @throws RemotingException
     */
    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {

        // 1. 当发生 ExecutionException 异常
        if (exception instanceof ExecutionException) {
            ExecutionException e = (ExecutionException) exception;
            Object msg = e.getRequest();
            // 1.1 消息是请求时
            if (msg instanceof Request) {
                Request req = (Request) msg;
                // 1.2 需要响应时且非心跳请求
                if (req.isTwoWay() && !req.isHeartbeat()) {
                    // 发送状态码为 SERVER_ERROR 的响应
                    Response res = new Response(req.getId(), req.getVersion());
                    res.setStatus(Response.SERVER_ERROR);
                    res.setErrorMessage(StringUtils.toString(e));
                    channel.send(res);
                    return;
                }
            }
        }

        // 2. 创建 channel 相应的 HeaderExchangeChannel 并将两者绑定.
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            // 3. 通知上层 ExchangeHandler 实现的 caught() 方法
            handler.caught(exchangeChannel, exception);
        } finally {
            // 4. 若channel已经断开，则 解绑 channel 与 HeaderExchangeChannel 的联系
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    /**
     * 获取被装饰的 ChannelHandler
     *
     * @return
     */
    @Override
    public ChannelHandler getHandler() {
        // 如果被装饰的 ChannelHandler 属于装饰者类型就获取其装饰的 handler
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }
}
