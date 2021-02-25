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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractClient;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.TimeUnit;

/**
 * NettyClient 继承 AbstractNettyClient抽象类，Dubbo的Netty客户端实现类
 */
public class NettyClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    /**
     * 线程组
     */
    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(Constants.DEFAULT_IO_THREADS, new DefaultThreadFactory("NettyClientWorker", true));

    /**
     * 引导类
     */
    private Bootstrap bootstrap;

    /**
     * Netty 的 通道。使用volatitle 修饰符。因为客户端可能会断开连接，需要保证多线程的可见性
     */
    private volatile Channel channel;

    public NettyClient(final URL url, final ChannelHandler handler) throws RemotingException {
        // wrapChannelHandler方法，包装ChannelHandler，其中实现了 Dubbo 线程模型的功能。 todo
        super(url, wrapChannelHandler(url, handler));
    }

    /**
     * 启动客户端
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {

        // 创建Dubbo NettyClientHandler 对象。注意传入的第二个参数是 NettyClient 对象本身，因为 NettyClient 是ChannelHander的子类
        //  NettyClientHandler 会将数据委托给这个 NettyClient
        final NettyClientHandler nettyClientHandler = new NettyClientHandler(getUrl(), this);
        // 实例化 Netty 客户端引导类
        bootstrap = new Bootstrap();
        bootstrap
                // 设置它的线程组
                .group(nioEventLoopGroup)
                // 设置可选项 (开启 KeepAlive)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                //.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getTimeout())

                // 设置 客户端对应的Channel类型
                .channel(NioSocketChannel.class);

        // 设置连接超时时间
        if (getConnectTimeout() < 3000) {
            bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);
        } else {
            bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getConnectTimeout());
        }

        // 设置处理器执行链
        bootstrap.handler(new ChannelInitializer() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                // 创建 NettyCoderAdapter 对象
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyClient.this);
                // 获取Pipeline
                ch.pipeline()
                        //.addLast("logging",new LoggingHandler(LogLevel.INFO)) // 设置日志，方便调试
                        // 解码
                        .addLast("decoder", adapter.getDecoder())
                        // 编码
                        .addLast("encoder", adapter.getEncoder())
                        // 处理器
                        .addLast("handler", nettyClientHandler);
            }
        });
    }

    /**
     * 连接服务器,建立Channel
     *
     * @throws Throwable
     */
    @Override
    protected void doConnect() throws Throwable {

        long start = System.currentTimeMillis();
        // 连接服务器
        ChannelFuture future = bootstrap.connect(getConnectAddress());
        try {

            // 等待连接成功或者超时
            boolean ret = future.awaitUninterruptibly(getConnectTimeout(), TimeUnit.MILLISECONDS);

            // 连接成功
            if (ret && future.isSuccess()) {

                // 取出连接的通道
                Channel newChannel = future.channel();

                try {
                    // 如果存在老的连接通道，就把老的关闭
                    Channel oldChannel = NettyClient.this.channel;
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            // 关闭老的通道
                            oldChannel.close();
                        } finally {
                            // 移除老的通道的相关的缓存
                            NettyChannel.removeChannelIfDisconnected(oldChannel);
                        }
                    }
                } finally {
                    // 若NettyClient 被关闭，就关闭新的连接
                    if (NettyClient.this.isClosed()) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close new netty channel " + newChannel + ", because the client closed.");
                            }
                            newChannel.close();
                        } finally {
                            NettyClient.this.channel = null;
                            NettyChannel.removeChannelIfDisconnected(newChannel);
                        }

                        // 更新连接通道，即设置新的连接通道到 channel 属性
                    } else {
                        NettyClient.this.channel = newChannel;
                    }
                }

                // 发生异常，则抛出
            } else if (future.cause() != null) {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + ", error message is:" + future.cause().getMessage(), future.cause());

                // 没有结果【连接超时】，抛出RemotingException异常
            } else {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + " client-side timeout "
                        + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion());
            }
        } finally {
            if (!isConnected()) {
                //future.cancel(true);
            }
        }
    }

    /**
     * 断开连接 - 清除channel 关联的缓存
     *
     * @throws Throwable
     */
    @Override
    protected void doDisConnect() throws Throwable {
        try {
            NettyChannel.removeChannelIfDisconnected(channel);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    @Override
    protected void doClose() throws Throwable {
        //can't shutdown nioEventLoopGroup
        //nioEventLoopGroup.shutdownGracefully();
    }

    @Override
    protected com.alibaba.dubbo.remoting.Channel getChannel() {
        Channel c = channel;
        if (c == null || !c.isActive())
            return null;
        return NettyChannel.getOrAddChannel(c, getUrl(), this);
    }

}
