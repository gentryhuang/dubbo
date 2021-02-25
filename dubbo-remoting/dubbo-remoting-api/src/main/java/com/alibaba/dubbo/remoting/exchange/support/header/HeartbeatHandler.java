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
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.transport.AbstractChannelHandlerDelegate;

/**
 * 心跳处理器，专门处理心跳消息的ChannelHandler实现
 */
public class HeartbeatHandler extends AbstractChannelHandlerDelegate {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatHandler.class);

    /**
     * 设置Channel的读时间戳 的 key
     */
    public static String KEY_READ_TIMESTAMP = "READ_TIMESTAMP";
    /**
     * 设置Channel的写时间戳 的 key
     */
    public static String KEY_WRITE_TIMESTAMP = "WRITE_TIMESTAMP";

    /**
     * 装饰 ChannelHandler
     *
     * @param handler
     */
    public HeartbeatHandler(ChannelHandler handler) {
        super(handler);
    }

    /**
     * 连接完成时，设置通道的最后读写时间
     *
     * @param channel
     * @throws RemotingException
     */
    @Override
    public void connected(Channel channel) throws RemotingException {
        setReadTimestamp(channel);
        setWriteTimestamp(channel);
        // 设置最后读写时间后，传递给底层的 ChannelHandler 对象进行处理
        handler.connected(channel);
    }

    /**
     * 连接断开时，清空通道的最后读写时间
     *
     * @param channel
     * @throws RemotingException
     */
    @Override
    public void disconnected(Channel channel) throws RemotingException {
        clearReadTimestamp(channel);
        clearWriteTimestamp(channel);
        // 清理读写时间后，传递给底层的 ChannelHandler 对象进行处理
        handler.disconnected(channel);
    }

    /**
     * 发送消息后，设置最后写的时间
     *
     * @param channel
     * @param message
     * @throws RemotingException
     */
    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        setWriteTimestamp(channel);
        // 记录最后写时间后，传递给底层的 ChannelHandler 对象进行处理
        handler.sent(channel, message);
    }

    /**
     * 收到消息，设置最后读时间
     * 1. 收到心跳请求的时候，会生成相应的心跳响应并返回；
     * 2. 收到心跳响应的时候，会打印相应的日志；
     * 3. 在收到其他类型的消息时，会传递给底层的 ChannelHandler 对象进行处理
     *
     * @param channel
     * @param message
     * @throws RemotingException
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {

        // 1 设置最后读时间
        setReadTimestamp(channel);

        // 2 收到心跳请求，则生成对应的心跳响应并返回
        if (isHeartbeatRequest(message)) {
            Request req = (Request) message;
            // 需要响应
            if (req.isTwoWay()) {
                // 设置请求id，为了和请求一一对应
                Response res = new Response(req.getId(), req.getVersion());
                // 心跳事件
                res.setEvent(Response.HEARTBEAT_EVENT);
                // 心跳响应
                channel.send(res);
                if (logger.isInfoEnabled()) {
                    int heartbeat = channel.getUrl().getParameter(Constants.HEARTBEAT_KEY, 0);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Received heartbeat from remote channel " + channel.getRemoteAddress()
                                + ", cause: The channel has no data-transmission exceeds a heartbeat period"
                                + (heartbeat > 0 ? ": " + heartbeat + "ms" : ""));
                    }
                }
            }
            return;
        }

        // 3 收到心跳响应，则打印日志
        if (isHeartbeatResponse(message)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Receive heartbeat response in thread " + Thread.currentThread().getName());
            }
            return;
        }

        // 4 其它类型消息，则传递给底层的 ChannelHandler 对象进行处理
        handler.received(channel, message);
    }

    /**
     * 设置 Channel 读时间戳
     *
     * @param channel
     */
    private void setReadTimestamp(Channel channel) {
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
    }

    /**
     * 设置 Channel 写时间戳
     *
     * @param channel
     */
    private void setWriteTimestamp(Channel channel) {
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
    }

    /**
     * 清理 Channel 中读时间戳
     *
     * @param channel
     */
    private void clearReadTimestamp(Channel channel) {
        channel.removeAttribute(KEY_READ_TIMESTAMP);
    }

    /**
     * 清理 Channel 中写时间戳
     *
     * @param channel
     */
    private void clearWriteTimestamp(Channel channel) {
        channel.removeAttribute(KEY_WRITE_TIMESTAMP);
    }

    /**
     * 是否是心跳请求
     *
     * @param message
     * @return
     */
    private boolean isHeartbeatRequest(Object message) {
        return message instanceof Request && ((Request) message).isHeartbeat();
    }

    /**
     * 是否是心跳响应
     *
     * @param message
     * @return
     */
    private boolean isHeartbeatResponse(Object message) {
        return message instanceof Response && ((Response) message).isHeartbeat();
    }
}
