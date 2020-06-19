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
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.transport.AbstractServer;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NettyServer Netty服务器实现类
 * <p>
 * dubbo 默认使用的NettyServer是基于netty3.x版本实现的，相对比较老了。Dubbo 也提供了netty4.x版本的NettyServer，可以按需配置
 */
public class NettyServer extends AbstractServer implements Server {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    /**
     * Dubbo 通道集合
     * key: ip:port
     * value: Dubbo Channel
     */
    private Map<String, Channel> channels;
    /**
     * 服务端引导
     */
    private ServerBootstrap bootstrap;

    /**
     * Netty 的 Channel
     */
    private org.jboss.netty.channel.Channel channel;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        /**
         * 1 为providerUrl添加参数 threadname=DubboServerHandler-ip:port 【ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME))】
         * 2 使用 ChannelHandlers.wrap(DecodeHandler对象,providerUrl)对DecodeHandler对象进行三层包装，最终得到MultiMessageHandler实例子。实现Dubbo 线程模型的功能
         * 3 调用父类的构造方法初始化NettyServer的各个属性，最后启动Netty
         */
        super(url, ChannelHandlers.wrap(handler, ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME)));
    }

    /**
     * 启动Netty服务，监听客户端链接
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {
        // 设置日志工厂
        NettyHelper.setNettyLoggerFactory();

        // 创建boss 和 worker 线程池 【注意：Netty3和Netty4是完全不一样的】
        ExecutorService boss = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerBoss", true));
        ExecutorService worker = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerWorker", true));

        // 基于 boss,worker创建Netty的ChannelFactory 工厂对象
        ChannelFactory channelFactory = new NioServerSocketChannelFactory(boss, worker, getUrl().getPositiveParameter(Constants.IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS));

        // 初始化 ServerBootstrap 引导类
        bootstrap = new ServerBootstrap(channelFactory);

        // 创建 Dubbo 的 NettyHandler 对象
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);

        // 设置 channels 属性
        channels = nettyHandler.getChannels();
        // https://issues.jboss.org/browse/NETTY-365
        // https://issues.jboss.org/browse/NETTY-379
        // final Timer timer = new HashedWheelTimer(new NamedThreadFactory("NettyIdleTimer", true));
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                // 创建 NettyCodecAdapter对象
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                // 获取ChannelPipeline
                ChannelPipeline pipeline = Channels.pipeline();
                /*int idleTimeout = getIdleTimeout();
                if (idleTimeout > 10000) {
                    pipeline.addLast("timer", new IdleStateHandler(timer, idleTimeout / 1000, 0, 0));
                }*/
                // 设置解码器
                pipeline.addLast("decoder", adapter.getDecoder());
                // 设置编码器
                pipeline.addLast("encoder", adapter.getEncoder());
                // 设置处理器
                pipeline.addLast("handler", nettyHandler);
                return pipeline;
            }
        });


        //  服务器绑定端口监听
        channel = bootstrap.bind(getBindAddress());
    }

    /**
     * 关闭服务器
     *
     * @throws Throwable
     */
    @Override
    protected void doClose() throws Throwable {
        try {
            // 通道不为空就关闭通道
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        // 关闭连接到服务器的客户端通道
        try {
            Collection<com.alibaba.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && !channels.isEmpty()) {
                for (com.alibaba.dubbo.remoting.Channel channel : channels) {
                    try {
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        // 优雅关闭ServerBootstrap
        try {
            if (bootstrap != null) {
                // 释放 ServerBootstrap 相关的资源。
                bootstrap.releaseExternalResources();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        // 清空连接到服务器的客户端通道
        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();
        for (Channel channel : this.channels.values()) {
            if (channel.isConnected()) {
                chs.add(channel);
            } else {
                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
            }
        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean isBound() {
        return channel.isBound();
    }

}
