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
package com.alibaba.dubbo.remoting.transport.netty4;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractChannel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 实现 AbstractChannel 抽象类。对Netty的Channel的装饰
 */
final class NettyChannel extends AbstractChannel {

    private static final Logger logger = LoggerFactory.getLogger(NettyChannel.class);

    /**
     * 通道映射集合，Netty的Channel 到 Dubbo的Channel 的映射集合
     * key: Netty 的 Channel
     * value: NettyChannel
     */
    private static final ConcurrentMap<Channel, NettyChannel> channelMap = new ConcurrentHashMap<Channel, NettyChannel>();

    /**
     * Netty的Channel 【NettyChannel是传入的Netty的Channel的装饰器，它里面每个实现方法都会调用Netty的Channel】
     * 和当前的 Dubbo Channel对象一一对应
     */
    private final Channel channel;

    /**
     * 属性集合
     * 当前Channel 中附加属性，都会记录到该Map中
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();

    /**
     * 装饰 Netty Channel
     *
     * @param channel
     * @param url
     * @param handler
     */
    private NettyChannel(Channel channel, URL url, ChannelHandler handler) {
        super(url, handler);
        if (channel == null) {
            throw new IllegalArgumentException("netty channel == null;");
        }
        this.channel = channel;
    }

    /**
     * 创建NettyChannel 对象
     *
     * @param ch      Netty的Channel
     * @param url     URL
     * @param handler 通道处理器
     * @return Dubbo 的 NettyChannel
     */
    static NettyChannel getOrAddChannel(Channel ch, URL url, ChannelHandler handler) {
        if (ch == null) {
            return null;
        }
        NettyChannel ret = channelMap.get(ch);
        if (ret == null) {
            NettyChannel nettyChannel = new NettyChannel(ch, url, handler);
            // 处于连接中
            if (ch.isActive()) {
                // 添加到 通道集合中
                ret = channelMap.putIfAbsent(ch, nettyChannel);
            }
            if (ret == null) {
                ret = nettyChannel;
            }
        }
        return ret;
    }

    /**
     * 移除NettyChannel对象
     *
     * @param ch Netty的Channel
     */
    static void removeChannelIfDisconnected(Channel ch) {
        // Netty的Channel 未连接
        if (ch != null && !ch.isActive()) {
            channelMap.remove(ch);
        }
    }

    /**
     * 获取本机地址
     *
     * @return
     */
    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    /**
     * 获取远程地址
     *
     * @return
     */
    @Override
    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    /**
     * Channel 是否可用
     *
     * @return
     */
    @Override
    public boolean isConnected() {
        return !isClosed() && channel.isActive();
    }

    /**
     * 发送消息
     * 会通过底层关联的Netty框架Channel将数据发送到对端，可以通过第二个参数指定是否等待发送操作结束
     *
     * @param message
     * @param sent    true: 会等待消息发出，消息发送失败会抛出异常；  false: 不等待消息发出，将消息放入IO队列，即可返回
     * @throws RemotingException
     */
    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 检查连接是否可用
        super.send(message, sent);

        // 是否执行成功。 如果不需要等待发送成功（sent = false），默认就是成功状态
        boolean success = true;

        int timeout = 0;
        try {
            // 使用Netty 的 Channel 发送消息
            ChannelFuture future = channel.writeAndFlush(message);
            // 为true的话，会等待消息发送成功或者超时
            if (sent) {
                timeout = getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
                success = future.await(timeout);
            }

            // 发生异常就抛出
            Throwable cause = future.cause();
            if (cause != null) {
                throw cause;
            }
        } catch (Throwable e) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress() + ", cause: " + e.getMessage(), e);
        }

        // 发送失败，抛出异常
        if (!success) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress()
                    + "in timeout(" + timeout + "ms) limit");
        }
    }

    /**
     * 关闭通道
     */
    @Override
    public void close() {
        try {
            // 标记关闭 ，设置 com.alibaba.dubbo.remoting.transport.AbstractPeer.closed 的值
            super.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            // 移除断开连接的Channel对应的Dubbo NettyChannel
            removeChannelIfDisconnected(channel);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            // 清空属性 attributes
            attributes.clear();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close netty channel " + channel);
            }

            // 关闭Netty 的 Channel，注意在关闭前对一些其它资源进行清理工作。
            channel.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (value == null) { // The null value unallowed in the ConcurrentHashMap.
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @Override
    public void removeAttribute(String key) {
        attributes.remove(key);
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
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        NettyChannel other = (NettyChannel) obj;
        if (channel == null) {
            if (other.channel != null) return false;
        } else if (!channel.equals(other.channel)) return false;
        return true;
    }

    @Override
    public String toString() {
        return "NettyChannel [channel=" + channel + "]";
    }

}
