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
package com.alibaba.dubbo.remoting.transport.netty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractClient;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * NettyClient Dubbo的Netty客户端实现类
 */
public class NettyClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    // ChannelFactory's closure has a DirectMemory leak, using static to avoid
    // https://issues.jboss.org/browse/NETTY-424
    private static final ChannelFactory channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(new NamedThreadFactory("NettyClientBoss", true)),
            Executors.newCachedThreadPool(new NamedThreadFactory("NettyClientWorker", true)),
            Constants.DEFAULT_IO_THREADS);

    /**
     * 客户端引导
     */
    private ClientBootstrap bootstrap;

    /**
     * Netty 通道 - 连接到Netty的服务端后，该属性的值就是对应的链接，即Netty的通道 {@link org.jboss.netty.channel.Channel}
     */
    private volatile Channel channel;

    public NettyClient(final URL url, final ChannelHandler handler) throws RemotingException {
        super(url, wrapChannelHandler(url, handler));
    }

    /**
     * 启动 netty客户端
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {

        // 设置日志工厂
        NettyHelper.setNettyLoggerFactory();

        // 实例化 客户端引导
        bootstrap = new ClientBootstrap(channelFactory);
        // 可选配置项
        // @see org.jboss.netty.channel.socket.SocketChannelConfig
        bootstrap.setOption("keepAlive", true);
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("connectTimeoutMillis", getConnectTimeout());

        // 创建 NettyHandler对象
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);

        // 设置处理器链
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                // 创建 NettyCodecAdapter 对象
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyClient.this);
                // 获取 ChannelPipeline
                ChannelPipeline pipeline = Channels.pipeline();
                // 解码
                pipeline.addLast("decoder", adapter.getDecoder());
                // 编码
                pipeline.addLast("encoder", adapter.getEncoder());
                // 处理器
                pipeline.addLast("handler", nettyHandler);
                return pipeline;
            }
        });
    }

    /**
     * 连接Netty服务端
     *
     * @throws Throwable
     */
    @Override
    protected void doConnect() throws Throwable {

        long start = System.currentTimeMillis();
        // 连接服务端
        ChannelFuture future = bootstrap.connect(getConnectAddress());
        try {

            // 等待连接成功或者超时
            boolean ret = future.awaitUninterruptibly(getConnectTimeout(), TimeUnit.MILLISECONDS);

            // 连接成功的情况
            if (ret && future.isSuccess()) {
                Channel newChannel = future.getChannel();
                newChannel.setInterestOps(Channel.OP_READ_WRITE);
                try {
                    // 关闭旧的连接
                    Channel oldChannel = NettyClient.this.channel;
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            oldChannel.close();
                        } finally {
                            NettyChannel.removeChannelIfDisconnected(oldChannel);
                        }
                    }
                } finally {
                    // 若 NettyClient 被关闭，关闭连接
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

                        // 重置连接
                    } else {
                        NettyClient.this.channel = newChannel;
                    }
                }

                // 发生异常，抛出 RemotingException 异常
            } else if (future.getCause() != null) {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + ", error message is:" + future.getCause().getMessage(), future.getCause());

                // 无结果（连接超时），抛出 RemotingException 异常
            } else {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + " client-side timeout "
                        + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion());
            }
        } finally {
            // 未连接，就取消任务
            if (!isConnected()) {
                future.cancel();
            }
        }
    }

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
        /*try {
            bootstrap.releaseExternalResources();
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }*/
    }

    /**
     * 获取一个封装了Netty的Dubbo Channel
     *
     * @return
     */
    @Override
    protected com.alibaba.dubbo.remoting.Channel getChannel() {
        Channel c = channel;
        if (c == null || !c.isConnected()) {
            return null;
        }
        //获取NettyChannel对象，内部封装了Netty的Channel通道
        return NettyChannel.getOrAddChannel(c, getUrl(), this);
    }

}
