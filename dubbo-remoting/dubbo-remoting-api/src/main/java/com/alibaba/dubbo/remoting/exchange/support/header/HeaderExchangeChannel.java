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
 * HeaderExchangeChannel  实现ExchangeChannel接口，基于消息头部(Header)的信息交换通道实现类。
 * 1  HeaderExchangeChannel 是 Channel 的装饰器，封装了一个Channel对象，send 和 request 方法的实现都委托给这个 Channel 对象实现。
 * 2 其中的 request 方法中完成 Default 对象创建后，会将请求通过底层的 Dubbo Channel 发送出去，发送过程中会触发道道关联的 ChannelHandler 的 sent 方法，
 * 其中 HeaderExchangeHandler 会调用 DefaultFuture.sent 方法更新 sent 字段，记录请求发送的时间戳，后续如果响应超时，则会将该发送时间戳添加到提示信息中。
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    /**
     * 作为通道的一个属性字段
     */
    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";
    /**
     * 通道 [NettyClient]
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
     * @param ch Channel
     * @return
     */
    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        // 通过 ch.getAttribute(CHANNEL_KEY) 键值，保证创建唯一的HeaderExchangeChannel对象
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        if (ret == null) {
            ret = new HeaderExchangeChannel(ch);
            // ch 必须是已连接状态，否则不会绑定对应的 HeaderExchangeChannel 对象
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
        // ch 断开了连接，则解除邦定的 HeaderExchangeChannel 对象
        if (ch != null && !ch.isConnected()) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    /**
     * Endpoint 方法
     *
     * @param message
     * @throws RemotingException
     */
    @Override
    public void send(Object message) throws RemotingException {
        // 默认不等待消息发出就返回
        send(message, getUrl().getParameter(Constants.SENT_KEY, false));
    }

    /**
     * Endpoint 方法
     *
     * @param message
     * @param sent    true: 会等待消息发出，消息发送失败会抛出异常；  false: 不等待消息发出，将消息放入IO队列，即可返回
     * @throws RemotingException
     */
    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 如果处于关闭状态，则抛出异常
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }

        // 如果消息是 Request、Response、String 类型，直接交给 Channel.send() 方法
        if (message instanceof Request
                || message instanceof Response
                || message instanceof String) {
            channel.send(message, sent);
        } else {
            // 构建 Request 对象，并且不需要响应
            Request request = new Request();
            request.setVersion(Version.getProtocolVersion());
            request.setTwoWay(false);
            request.setData(message);
            channel.send(request, sent);
        }
    }

    /**
     * 发送请求
     *
     * @param request
     * @return
     * @throws RemotingException
     */
    @Override
    public ResponseFuture request(Object request) throws RemotingException {
        // 请求超时时间，默认 1000
        return request(request, channel.getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
    }

    /**
     * 发送请求
     *
     * @param request
     * @param timeout 请求超时时间
     * @return
     * @throws RemotingException
     */
    @Override
    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        // 如果已经关闭，不能发起请求
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }
        // 创建请求，并初始化请求编号
        Request req = new Request();
        // Dubbo 版本
        req.setVersion(Version.getProtocolVersion());
        // 需要响应
        req.setTwoWay(true);
        // 具体数据 为 RpcInvocation
        req.setData(request);
        /**
         *  创建DefaultFuture 对象,该对象在接受到服务端响应的时候会用到。
         *  1 netty从宏观上看是一个异步非阻塞的框架，所以当执行channel.send(req)的时候，其内部执行到netty发送消息时，不会等待结果，直接返回。为了实现“异步转为同步”，使用了DefaultFuture这个辅助类
         *  2 在创建 DefaultFuture对象时，会把该对象放到缓存Map {@link DefaultFuture#FUTURES }
         *  3 在NettyClient.request(Object request, int timeout)，在还没有等到客户端的响应回来的时候，就直接将future返回了
         *  4 netty是异步非阻塞的，那么假设现在我发了1w个Request，后来返回来1w个Response，那么怎么对应Request和Response呢？如果对应不上，最起码的唤醒就会有问题。为了解决这个问题，Request和Response中都有一个属性id，Response中的属性mId就是Request中的mId
         */
        DefaultFuture future = new DefaultFuture(channel, req, timeout);
        try {
            //  发送请求。需要注意的是，NettyClient中并未实现send 方法，该方法继承自父类AbstractPeer
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
            // 请求处理完或者关闭超时，则结束
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

    /**
     * 关闭通道
     */
    @Override
    public void close() {
        try {
            // 执行 channel 的关闭动作
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
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
