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
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Client;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * DefaultMessageClient
 * <p>
 * 实现 ExchangeClient 接口，基于消息头部( Header )的信息交换客户端实现类。主要实现了心跳逻辑
 */
public class HeaderExchangeClient implements ExchangeClient {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeClient.class);
    /**
     * 定时任务线程池
     */
    private static final ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(2, new NamedThreadFactory("dubbo-remoting-client-heartbeat", true));
    /**
     * Transport 层的 Client 实例
     */
    private final Client client;
    /**
     * 信息交换通道，Client 和 Server 建立的连接
     */
    private final ExchangeChannel channel;
    /**
     * 定时任务Future
     */
    private ScheduledFuture<?> heartbeatTimer;
    /**
     * 心跳间隔
     */
    private int heartbeat;
    /**
     * 心跳超时时间
     */
    private int heartbeatTimeout;

    /**
     * @param client        Transport 层的 Client 实例
     * @param needHeartbeat 是否开启心跳
     */
    public HeaderExchangeClient(Client client, boolean needHeartbeat) {
        if (client == null) {
            throw new IllegalArgumentException("client == null");
        }

        // 设置 client 属性
        this.client = client;
        // 创建 HeaderExchangeChannel 对象，对 Transport层的Client进行装饰
        this.channel = new HeaderExchangeChannel(client);

        // 获取 Dubbo 版本
        String dubbo = client.getUrl().getParameter(Constants.DUBBO_VERSION_KEY);

        // 读取心跳相关的配置,默认开启心跳机制
        this.heartbeat = client.getUrl().getParameter(Constants.HEARTBEAT_KEY, dubbo != null && dubbo.startsWith("1.0.") ? Constants.DEFAULT_HEARTBEAT : 0);
        this.heartbeatTimeout = client.getUrl().getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartbeat * 3);

        // 避免心跳间隔太短
        if (heartbeatTimeout < heartbeat * 2) {
            throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
        }

        // 是否启动心跳
        if (needHeartbeat) {
            // 启动心跳定时任务
            startHeartbeatTimer();
        }
    }

    // -------------------------- HeaderExchangeClient 中很多方法只有一行代码，即调用HeaderExchangeChannel 对象的同签名方法。它主要的作用是封装了一些关于心跳检测的逻辑  -------------/


    //------------------------ ExchangeChannel 接口方法的实现 -------------------/
    @Override
    public ResponseFuture request(Object request) throws RemotingException {
        return channel.request(request);
    }

    @Override
    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        return channel.request(request, timeout);
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return channel.getExchangeHandler();
    }

    @Override
    public void close(int timeout) {
        // Mark the client into the closure process
        startClose();
        doClose();
        channel.close(timeout);
    }


    //-------------------------- Endpoint 接口方法的实现 ----------------------/
    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    // 获取底层Channel关联的ChannelHandler。 Channel接口继承Endpoint接口是有原因的哟。
    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    // Channel接口继承Endpoint接口是有原因的哟。
    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public void send(Object message) throws RemotingException {
        channel.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        channel.send(message, sent);
    }

    @Override
    public boolean isClosed() {
        return channel.isClosed();
    }

    @Override
    public void close() {
        doClose();
        channel.close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }


    //------------------------------- Channel 接口方法的实现 -------------------------/
    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
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


    //----------------------------- Client 接口方法的实现 ---------------------------/
    @Override
    public void reconnect() throws RemotingException {
        client.reconnect();
    }


    @Override
    public void reset(URL url) {
        client.reset(url);
    }

    @Override
    @Deprecated
    public void reset(com.alibaba.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }


    /**
     * 开启心跳定时任务
     */
    private void startHeartbeatTimer() {
        // 开启一个新的心跳定时任务时，需要停止原有的定时任务
        stopHeartbeatTimer();

        // 开启新的定时任务
        if (heartbeat > 0) {
            heartbeatTimer = scheduled.scheduleWithFixedDelay(
                    /**
                     * 创建心跳任务
                     */
                    new HeartBeatTask(new HeartBeatTask.ChannelProvider() {
                        /**
                         * 每个客户端端侧只有一个通道，这里是 HeaderExchangeClient 对象自己
                         * @return
                         */
                        @Override
                        public Collection<Channel> getChannels() {
                            return Collections.<Channel>singletonList(HeaderExchangeClient.this);
                        }
                    }, heartbeat, heartbeatTimeout),
                    heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 停止定时任务
     */
    private void stopHeartbeatTimer() {
        // 如果心跳定时任务没有取消，则尝试取消该任务。
        if (heartbeatTimer != null && !heartbeatTimer.isCancelled()) {
            try {
                heartbeatTimer.cancel(true);
                scheduled.purge();
            } catch (Throwable e) {
                if (logger.isWarnEnabled()) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        heartbeatTimer = null;
    }

    private void doClose() {
        stopHeartbeatTimer();
    }

    @Override
    public String toString() {
        return "HeaderExchangeClient [channel=" + channel + "]";
    }
}
