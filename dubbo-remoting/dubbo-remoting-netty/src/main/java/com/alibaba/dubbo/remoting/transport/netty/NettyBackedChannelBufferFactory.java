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

import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferFactory;

import org.jboss.netty.buffer.ChannelBuffers;

import java.nio.ByteBuffer;

/**
 * Wrap netty dynamic channel buffer.
 * <p>
 * 实现 ChannelBufferFactory 接口，创建 NettyBackedChannelBuffer 的工厂
 */
public class NettyBackedChannelBufferFactory implements ChannelBufferFactory {

    /**
     * 单例
     */
    private static final NettyBackedChannelBufferFactory INSTANCE = new NettyBackedChannelBufferFactory();

    public static ChannelBufferFactory getInstance() {
        return INSTANCE;
    }


    @Override
    public ChannelBuffer getBuffer(int capacity) {
        // 使用 Netty 的 ChannelBuffers 方法创建 org.jboss.netty.buffer.ChannelBuffer
        return new NettyBackedChannelBuffer(ChannelBuffers.dynamicBuffer(capacity));
    }


    @Override
    public ChannelBuffer getBuffer(byte[] array, int offset, int length) {
        // 创建 Netty3 ChannelBuffer 对象
        org.jboss.netty.buffer.ChannelBuffer buffer = ChannelBuffers.dynamicBuffer(length);
        // 写入数据
        buffer.writeBytes(array, offset, length);
        // 创建 NettyBackedChannelBuffer 对象
        return new NettyBackedChannelBuffer(buffer);
    }


    @Override
    public ChannelBuffer getBuffer(ByteBuffer nioBuffer) {
        // 1 使用 Netty 的 ChannelBuffers 方法创建 org.jboss.netty.buffer.ChannelBuffer
        // 2 创建 NettyBackedChannelBuffer
        return new NettyBackedChannelBuffer(ChannelBuffers.wrappedBuffer(nioBuffer));
    }
}
