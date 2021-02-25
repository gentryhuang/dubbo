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
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Request;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实现 ExchangeServer 接口，基于消息头部( Header )的信息交换服务器实现类
 */
public class HeaderExchangeServer implements ExchangeServer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 心跳检测定时线程池
     */
    private final ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(1, new NamedThreadFactory("dubbo-remoting-server-heartbeat", true));
    /**
     * Transport 层的服务
     */
    private final Server server;
    /**
     * 心跳检测定时器 Future
     */
    private ScheduledFuture<?> heartbeatTimer;
    /**
     * 心跳间隔时间，毫秒
     */
    private int heartbeat;
    /**
     * 心跳超时时间，毫秒
     */
    private int heartbeatTimeout;
    /**
     * 是否关闭，默认非关闭状态
     */
    private AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造方法
     *
     * @param server Transport 层的 Server 对象
     */
    public HeaderExchangeServer(Server server) {
        if (server == null) {
            throw new IllegalArgumentException("server == null");
        }
        this.server = server;

        // 读取心跳相关配置 ,注意在这之前 Constants.HEARTBEAT_KEY 对应的已经有值了： 如果配置了就是配置的，如果没有配置就是默认的60。
        this.heartbeat = server.getUrl().getParameter(Constants.HEARTBEAT_KEY, 0);

        // 注意 heartbeatTimeout：默认是heartbeat*3。（原因：假设一端发出一次heartbeatRequest，另一端在heartbeat内没有返回任何响应-包括正常请求响应和心跳响应，此时不能认为是连接断了，因为有可能还是网络抖动什么的导致了tcp包的重传超时等）
        this.heartbeatTimeout = server.getUrl().getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartbeat * 3);

        // 检测心跳超时时间是否合法
        if (heartbeatTimeout < heartbeat * 2) {
            throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
        }

        /**
         * dubbo的心跳默认是在heartbeat（默认是60s）内如果没有接收到消息，就会发送心跳消息，如果连着3次（默认180s）没有收到心跳响应，provider会关闭channel。
         */
        startHeartbeatTimer();
    }

    public Server getServer() {
        return server;
    }


    //------------------ 对 Server 的父类 Endpoint 接口实现  start -----------------/

    @Override
    public InetSocketAddress getLocalAddress() {
        return server.getLocalAddress();
    }

    @Override
    public URL getUrl() {
        return server.getUrl();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return server.getChannelHandler();
    }

    @Override
    public void send(Object message) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message, sent);
    }

    @Override
    public void close() {
        doClose();
        server.close();
    }

    /**
     * 优雅关闭,分两个阶段：
     * 1 正在关闭
     * 2 已经关闭
     *
     * @param timeout
     */
    @Override
    public void close(final int timeout) {
        // 将装饰的server的closing设置为 true，表示当前server处于正在关闭状态，不再与Client建立连接
        startClose();
        if (timeout > 0) {
            final long max = timeout;
            final long start = System.currentTimeMillis();

            // 发送 READONLY 事件给所有 Client ，表示Server不再接收新的消息
            if (getUrl().getParameter(Constants.CHANNEL_SEND_READONLYEVENT_KEY, true)) {
                // 广播客户端，READONLY_EVENT 事件消息
                sendChannelReadOnlyEvent();
            }

            // 等待 Client 与 当前Server 维持的长连接全部断开或超时
            while (HeaderExchangeServer.this.isRunning() && System.currentTimeMillis() - start < max) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }

        // 关闭心跳定时任务，且将自身closed设置为true
        doClose();

        // 关闭Transport层的Server
        server.close(timeout);
    }

    @Override
    public void startClose() {
        server.startClose();
    }

    @Override
    public boolean isClosed() {
        return server.isClosed();
    }


    /**
     * 关闭心跳定时器
     */
    private void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopHeartbeatTimer();
        try {
            scheduled.shutdown();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    //------------------ 对 Server 的父类 Endpoint 接口实现 end -----------------/


    //------------------ 对 Server 接口的实现  start -----------------/

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Collection<Channel> getChannels() {
        return (Collection) getExchangeChannels();
    }

    /**
     * 获取NettyServer中的全部channel连接
     *
     * @return
     */
    @Override
    public Collection<ExchangeChannel> getExchangeChannels() {
        Collection<ExchangeChannel> exchangeChannels = new ArrayList<ExchangeChannel>();
        // 获取 server 维护的 Channel 通道集合
        Collection<Channel> channels = server.getChannels();
        if (channels != null && !channels.isEmpty()) {
            for (Channel channel : channels) {
                // 根据 Channel 获取 Exchange 层的 Channel
                exchangeChannels.add(HeaderExchangeChannel.getOrAddChannel(channel));
            }
        }
        return exchangeChannels;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return getExchangeChannel(remoteAddress);
    }

    @Override
    public boolean isBound() {
        return server.isBound();
    }

    @Override
    @Deprecated
    public void reset(com.alibaba.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    //------------------ 对 Server 接口的实现  end -----------------/


    @Override
    public ExchangeChannel getExchangeChannel(InetSocketAddress remoteAddress) {
        Channel channel = server.getChannel(remoteAddress);
        return HeaderExchangeChannel.getOrAddChannel(channel);
    }


    /**
     * 重置服务器相关属性
     *
     * @param url
     */
    @Override
    public void reset(URL url) {
        // 重置服务器
        server.reset(url);
        try {
            if (url.hasParameter(Constants.HEARTBEAT_KEY) || url.hasParameter(Constants.HEARTBEAT_TIMEOUT_KEY)) {
                int h = url.getParameter(Constants.HEARTBEAT_KEY, heartbeat);
                int t = url.getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, h * 3);
                if (t < h * 2) {
                    throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
                }

                // 重置定时任务
                if (h != heartbeat || t != heartbeatTimeout) {
                    heartbeat = h;
                    heartbeatTimeout = t;
                    startHeartbeatTimer();
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }


    private boolean isRunning() {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {

            /**
             *  If there are any client connections,
             *  our server should be running.
             */

            // 存在任意一个Client与当前Server处于连接状态，Server 都不能不关闭
            if (channel.isConnected()) {
                return true;
            }
        }
        return false;
    }


    /**
     * 广播客户端，READONLY_EVENT 事件
     */
    private void sendChannelReadOnlyEvent() {

        // 创建 READONLY_EVENT 请求
        Request request = new Request();
        request.setEvent(Request.READONLY_EVENT);
        // 只读请求不需要响应
        request.setTwoWay(false);
        request.setVersion(Version.getProtocolVersion());

        // 发送给所有 Client
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            try {
                if (channel.isConnected()) {
                    channel.send(request, getUrl().getParameter(Constants.CHANNEL_READONLYEVENT_SENT_KEY, true));
                }
            } catch (RemotingException e) {
                logger.warn("send cannot write message error.", e);
            }
        }
    }


    /**
     * 发起心跳定时器
     */
    private void startHeartbeatTimer() {
        // 关闭原有定时任务
        stopHeartbeatTimer();
        // 发起新的定时任务
        if (heartbeat > 0) {
            /**
             * 启动scheduled中的定时线程，从启动该线程开始，每隔heartbeat执行一次HeartBeatTask任务（第一次执行是在启动线程后heartbeat时）
             */
            heartbeatTimer = scheduled.scheduleWithFixedDelay(
                    /**
                     * 创建心跳任务，channelProvider实例是HeaderExchangeServer中在启动线程定时执行器的时候创建的内部类
                     */
                    new HeartBeatTask(new HeartBeatTask.ChannelProvider() {
                        /**
                         * 获取需要心跳的通道
                         * @return
                         */
                        @Override
                        public Collection<Channel> getChannels() {
                            /**
                             * 获取NettyServer中的全部channel连接【Server 持有多条Client 连接的Channel】
                             */
                            return Collections.unmodifiableCollection(HeaderExchangeServer.this.getChannels());
                        }
                    }, heartbeat, heartbeatTimeout),
                    heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 暂停心跳定时任务
     */
    private void stopHeartbeatTimer() {
        try {
            ScheduledFuture<?> timer = heartbeatTimer;
            if (timer != null && !timer.isCancelled()) {
                timer.cancel(true);
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        } finally {
            heartbeatTimer = null;
        }
    }

}
