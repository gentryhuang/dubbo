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
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.DynamicChannelBuffer;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder;

import java.io.IOException;

/**
 * NettyCodecAdapter  dubbo的Netty 编解码适配器，将 Dubbo 编解码器 适配成 Netty3 的编码器和解码器。
 */
final class NettyCodecAdapter {

    /**
     * Netty 编码器
     */
    private final ChannelHandler encoder = new InternalEncoder();
    /**
     * Netty 解码器
     */
    private final ChannelHandler decoder = new InternalDecoder();
    /**
     * Dubbo 编解码器
     */
    private final Codec2 codec;
    /**
     * Dubbo URL
     */
    private final URL url;


    /**
     * 网络读写缓冲区大小  【Netty4 的NettyCodecAdapter不需要该属性】
     */
    private final int bufferSize;

    /**
     * Dubbo 的 ChannelHandler
     */
    private final com.alibaba.dubbo.remoting.ChannelHandler handler;

    public NettyCodecAdapter(Codec2 codec, URL url, com.alibaba.dubbo.remoting.ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
        // 设置 缓冲区大小
        int b = url.getPositiveParameter(Constants.BUFFER_KEY, Constants.DEFAULT_BUFFER_SIZE);
        this.bufferSize = b >= Constants.MIN_BUFFER_SIZE && b <= Constants.MAX_BUFFER_SIZE ? b : Constants.DEFAULT_BUFFER_SIZE;
    }

    public ChannelHandler getEncoder() {
        return encoder;
    }

    public ChannelHandler getDecoder() {
        return decoder;
    }

    /**
     * Netty3 编码器
     */
    @Sharable
    private class InternalEncoder extends OneToOneEncoder {

        @Override
        protected Object encode(ChannelHandlerContext ctx, Channel ch, Object msg) throws Exception {
            // 创建 ChannelBuffer 对象
            com.alibaba.dubbo.remoting.buffer.ChannelBuffer buffer =
                    com.alibaba.dubbo.remoting.buffer.ChannelBuffers.dynamicBuffer(1024);

            // 获得 NettyChannel 对象
            NettyChannel channel = NettyChannel.getOrAddChannel(ch, url, handler);
            try {
                // 编码
                codec.encode(channel, buffer, msg);
            } finally {
                // 若断开连接，则移除 NettyChannel 对象
                NettyChannel.removeChannelIfDisconnected(ch);
            }

            // 返回 Netty的ChannelBuffer
            return ChannelBuffers.wrappedBuffer(buffer.toByteBuffer());
        }
    }

    /**
     * 解码器
     */
    private class InternalDecoder extends SimpleChannelUpstreamHandler {

        /**
         * 未读完的消息 Buffer属性
         * 说明：
         * 在 #messageReceived(ctx, event) 方法中，我们在做拆包粘包的处理过程中，可能收到数据是不完整的。例如，不足以解析成一条 Dubbo Request那么，我们就需要将收到的，缓存到 buffer 中
         */
        private com.alibaba.dubbo.remoting.buffer.ChannelBuffer buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent event) throws Exception {
            Object o = event.getMessage();
            // 跳过 非 ChannelBuffer
            if (!(o instanceof ChannelBuffer)) {
                ctx.sendUpstream(event);
                return;
            }

            // 无可读，则跳过
            ChannelBuffer input = (ChannelBuffer) o;
            int readable = input.readableBytes();
            if (readable <= 0) {
                return;
            }

            // 合并 buffer + input 成 message
            com.alibaba.dubbo.remoting.buffer.ChannelBuffer message;


            // 有未读完的，需要拼接
            if (buffer.readable()) {
                // 是 DynamicChannelBuffer 类型
                if (buffer instanceof DynamicChannelBuffer) {
                    buffer.writeBytes(input.toByteBuffer());
                    message = buffer;

                    // 非 DynamicChannelBuffer 类型，转成DynamicChannelBuffer
                } else {
                    int size = buffer.readableBytes() + input.readableBytes();
                    message = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.dynamicBuffer(size > bufferSize ? size : bufferSize);
                    message.writeBytes(buffer, buffer.readableBytes());
                    message.writeBytes(input.toByteBuffer());
                }

                // 无未读完的，直接创建
            } else {
                message = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.wrappedBuffer(
                        input.toByteBuffer());
            }

            // 获得NettyChannel 对象
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.getChannel(), url, handler);
            Object msg;
            int saveReaderIndex;

            try {
                // 循环解析，直到结束
                do {
                    saveReaderIndex = message.readerIndex();
                    try {
                        msg = codec.decode(channel, message);
                    } catch (IOException e) {
                        buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;
                        throw e;
                    }
                    if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                        message.readerIndex(saveReaderIndex);
                        break;

                        // 解码到消息，触发一条消息  todo 一般不会走到这里
                    } else {
                        if (saveReaderIndex == message.readerIndex()) {
                            buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;
                            throw new IOException("Decode without read data.");
                        }
                        if (msg != null) {
                            Channels.fireMessageReceived(ctx, msg, event.getRemoteAddress());
                        }
                    }
                } while (message.readable());
            } finally {
                // 有剩余可读的，压缩并缓存
                if (message.readable()) {
                    message.discardReadBytes();
                    buffer = message;

                    // 完全读完，设置空 Buffer
                } else {
                    buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;
                }
                // 若断开连接，则移除NettyChannel 对象
                NettyChannel.removeChannelIfDisconnected(ctx.getChannel());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            ctx.sendUpstream(e);
        }
    }
}