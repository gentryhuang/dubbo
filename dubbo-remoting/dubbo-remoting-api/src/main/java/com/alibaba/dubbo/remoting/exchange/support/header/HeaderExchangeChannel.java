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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;

/**
 * ExchangeReceiver  实现ExchangeChannel接口，基于消息头部(Header)的信息交换通道实现类
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    /**
     * 作为通道的一个属性字段
     */
    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";
    /**
     * 通道
     */
    private final Channel channel;
    /**
     * 是否关闭
     */
    private volatile boolean closed = false;

    /**
     * HeaderExchangeChannel是传入 channel 属性的装饰器
     *
     * @param channel
     */
    HeaderExchangeChannel(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel == null");
        }
        this.channel = channel;
    }

    /**
     * 创建HeaderExchangeChannel 对象。
     *
     * @param ch
     * @return
     */
    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        // 通过 ch.getAttribute(CHANNEL_KEY) 键值，保证创建唯一的HeaderExchangeChannel对象 【必须是 已连接状态 】
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        if (ret == null) {
            ret = new HeaderExchangeChannel(ch);
            if (ch.isConnected()) {
                ch.setAttribute(CHANNEL_KEY, ret);
            }
        }
        return ret;
    }

    /**
     * 本质上就是移除 HeaderExchangeChannel对象，因为把HeaderExchangeChannel的Channel属性中的 CHANNEL_KEY 字段key移除，下次会重新创建 HeaderExchangeChannel对象
     *
     * @param ch
     */
    static void removeChannelIfDisconnected(Channel ch) {
        // 未连接
        if (ch != null && !ch.isConnected()) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    @Override
    public void send(Object message) throws RemotingException {
        send(message, getUrl().getParameter(Constants.SENT_KEY, false));
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }
        if (message instanceof Request
                || message instanceof Response
                || message instanceof String) {
            channel.send(message, sent);
        } else {
            Request request = new Request();
            request.setVersion(Version.getProtocolVersion());
            request.setTwoWay(false);
            request.setData(message);
            channel.send(request, sent);
        }
    }

    @Override
    public ResponseFuture request(Object request) throws RemotingException {
        return request(request, channel.getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
    }

    /**
     * 发送请求
     *
     * @param request
     * @param timeout
     * @return
     * @throws RemotingException
     */
    @Override
    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        // 如果已经关闭，不能发起请求
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }
        // 创建请求
        Request req = new Request();
        req.setVersion(Version.getProtocolVersion());
        // 需要响应
        req.setTwoWay(true);
        // 具体数据
        req.setData(request);
        // 创建DefaultFuture 对象
        DefaultFuture future = new DefaultFuture(channel, req, timeout);
        try {
            // 发送请求
            channel.send(req);
        } catch (RemotingException e) {
            // 发送请求失败就取消 DefaultFuture
            future.cancel();
            throw e;
        }
        // 返回 DefaultFuture 对象
        return future;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * 关闭通道
     */
    @Override
    public void close() {
        try {
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    /**
     * 优雅关闭
     *
     * @param timeout
     */
    @Override
    public void close(int timeout) {
        // 如果已经关闭，就直接返回
        if (closed) {
            return;
        }

        // 设置关闭标识，防止发起新的请求
        closed = true;

        // 等待请求完成
        if (timeout > 0) {
            long start = System.currentTimeMillis();
            // 有结果或者超时了，就结束
            while (DefaultFuture.hasFuture(channel) && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        // 关闭通道
        close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return (ExchangeHandler) channel.getChannelHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        HeaderExchangeChannel other = (HeaderExchangeChannel) obj;
        if (channel == null) {
            if (other.channel != null) return false;
        } else if (!channel.equals(other.channel)) return false;
        return true;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

}
