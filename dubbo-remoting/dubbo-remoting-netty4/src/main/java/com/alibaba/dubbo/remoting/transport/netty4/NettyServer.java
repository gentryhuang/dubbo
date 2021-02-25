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
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.transport.AbstractServer;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

/**
 * 实现 Server 接口，继承 AbstractServer 抽象类，Netty 服务器实现类
 * 说明：
 * NettyServer通过层层继承，拥有了很多类的职能，如 Endpoint、ChannelHandler、Server 多个接口的能力，其中关联了
 * ChannelHandler对象和Codec2对象，并最将编解码任务交给Codec2对象处理，将数据处理交给ChannelHandler处理，
 */
public class NettyServer extends AbstractServer implements Server {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    /**
     * 通道集合,这里是连接到服务器的客户端通道集合
     * key: ip:port
     * value: Channel
     */
    private Map<String, Channel> channels;

    /**
     * Netty 服务端的引导类
     */
    private ServerBootstrap bootstrap;

    /**
     * Channel
     */
    private io.netty.channel.Channel channel;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    /**
     * 创建 NettyServer 时，会对传入的 ChannelHandler 进行层层包装。
     * 其中在包装过程中， Dispatcher创建的ChanglHandler的过程都要创建一个线程池，然后保存到Datasource 中。 todo 2.7.7 对次做了优化
     *
     * @param url
     * @param handler
     * @throws RemotingException
     */
    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        // ChannelHandlers.wrap方法，用来包装 ChannelHandler，实现Dubbo 线程模型的功能
        super(url, ChannelHandlers.wrap(handler, ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME)));
    }

    /**
     * 启动服务器
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {

        // 创建引导类
        bootstrap = new ServerBootstrap();

        // 分别创建Boss线程组和Worker线程组
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("NettyServerBoss", true));
        workerGroup = new NioEventLoopGroup(getUrl().getPositiveParameter(Constants.IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS),
                new DefaultThreadFactory("NettyServerWorker", true));

        // 创建NettyServerHandler对象，注意传入的第二个参数是 NettyServer 对象本身，因为NettyServer是ChannelHander的子类
        // NettyServerHandler 会将数据委托给这个 ChannelHandler
        final NettyServerHandler nettyServerHandler = new NettyServerHandler(getUrl(), this);

        // 设置 channels 属性 【从NettyServerHandler对象中取Channel通道集合】
        channels = nettyServerHandler.getChannels();

        bootstrap
                // 设置线程组
                .group(bossGroup, workerGroup)
                // 服务端使用NioServerSocketChannel 作为传输通道
                .channel(NioServerSocketChannel.class)
                // 配置可选项，Netty 优化相关
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                // 设置入站通道处理器链
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        // 创建编解码适配器
                        NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                        // ChannelPipeline
                        ch.pipeline()
                                //.addLast("logging",new LoggingHandler(LogLevel.INFO)) // 打印日志，方便debug
                                // 解码
                                .addLast("decoder", adapter.getDecoder())
                                // 编码
                                .addLast("encoder", adapter.getEncoder())
                                // 处理器
                                .addLast("handler", nettyServerHandler);
                    }
                });

        // 服务器绑定端口监听，启动 Netty
        ChannelFuture channelFuture = bootstrap.bind(getBindAddress());
        channelFuture.syncUninterruptibly();

        channel = channelFuture.channel();

    }

    /**
     * 关闭服务器
     *
     * @throws Throwable
     */
    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // 关闭服务器通道
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        // 关闭连接到服务器的客户端通道
        try {
            Collection<com.alibaba.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && channels.size() > 0) {
                // 依次遍历连接到服务器的客户端通道，然后进行关闭操作
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

        // 优雅关闭线程组
        try {
            if (bootstrap != null) {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
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

    /**
     * 获得连接到服务器的客户端通道集合
     *
     * @return
     */
    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();

        for (Channel channel : this.channels.values()) {
            // 已连接，则加入结果集
            if (channel.isConnected()) {
                chs.add(channel);
                // 未连接，移除
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
        return channel.isActive();
    }

}
