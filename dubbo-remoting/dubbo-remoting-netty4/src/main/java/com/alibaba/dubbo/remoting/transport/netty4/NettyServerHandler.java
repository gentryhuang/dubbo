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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NettyClientHandler,NettyServerd的处理器。继承了Netty 的 ChannelDuplexHandler
 * <p>
 * 注意：
 *
 * @Sharable 注解主要用来标识一个ChannelHandler可以被安全地共享，即可以在多个Channel的ChannelPipeline中使用同一个ChannelHandler，而不必每一个
 * ChannelPipeline都重新new 一个新的ChannelHandler
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyServerHandler extends ChannelDuplexHandler {

    /**
     * Dubbo Channel 集合,即连接到当前服务器的Dubbo Channel集合
     * key: ip:port
     * value: Dubbo 的 Channel
     */
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();
    /**
     * URL
     */
    private final URL url;

    /**
     * Dubbo ChannelHandler。NettyServerHandler对每个事件的处理，会调用 handler的方法.
     */
    private final ChannelHandler handler;

    /**
     * todo
     * @param url
     * @param handler NettyServer 对象 ，NettyServerHandler对每个事件的处理，都会调用NettyServer 对应的方法？？？ todo 验证下
     */
    public NettyServerHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    //------------------------------- NettyServerHandler的每个实现的方法，处理都比较类似，数据交给handler做相应处理 ---------------------/

    /**
     * 连接创建触发该方法
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {

        // 交给下一个节点处理 【此处不调用也是可以的，NettyServerHandler没有下一个节点】
        ctx.fireChannelActive();

        // 创建Dubbo 的NettyChannel 对象
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);

        try {

            // 加入到 连接到服务器的Dubbo Channel集合 中
            if (channel != null) {
                // 通道相关的ip:port 作为key，这里时客户端的？？？ todo
                channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), channel);
            }

            // 交给 handler 处理，处理连接事件
            handler.connected(channel);
        } finally {
            /** 如果已经断开，就移除NettyChannel对象  {@link com.alibaba.dubbo.remoting.transport.netty4.NettyChannel.channelMap }*/
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    /**
     * 连接断开触发该方法
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            // 从缓存中移除 Netty Channel 对应的 Dubbo Channel
            channels.remove(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()));
            // 将断开连接事件交给 handler处理
            handler.disconnected(channel);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }


    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise future)
            throws Exception {

    }

    /**
     * 读取数据
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.received(channel, msg);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    /**
     *
     * @param ctx
     * @param msg
     * @param promise
     * @throws Exception
     */

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 将发送的数据继续向下传递
        super.write(ctx, msg, promise);
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.sent(channel, msg);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.caught(channel, cause);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }
}