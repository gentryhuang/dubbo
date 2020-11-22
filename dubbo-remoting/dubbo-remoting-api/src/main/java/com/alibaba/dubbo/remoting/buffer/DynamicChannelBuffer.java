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

package com.alibaba.dubbo.remoting.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * 实现 AbstractChannelBuffer 抽象类，基于动态的 Buffer 实现类。【基于传入的 ChannelBufferFactory创建的Buffer 实现类】
 */
public class DynamicChannelBuffer extends AbstractChannelBuffer {

    /**
     * ChannelBuffer 工厂
     */
    private final ChannelBufferFactory factory;
    /**
     * ChannelBuffer
     */
    private ChannelBuffer buffer;

    /**
     * 默认 HeapChannelBufferFactory
     *
     * @param estimatedLength
     */
    public DynamicChannelBuffer(int estimatedLength) {
        this(estimatedLength, HeapChannelBufferFactory.getInstance());
    }

    public DynamicChannelBuffer(int estimatedLength, ChannelBufferFactory factory) {
        if (estimatedLength < 0) {
            throw new IllegalArgumentException("estimatedLength: " + estimatedLength);
        }
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        // 设置 factory
        this.factory = factory;
        // 创建 buffer
        buffer = factory.getBuffer(estimatedLength);
    }

    @Override
    public void ensureWritableBytes(int minWritableBytes) {
        // 剩余空间充足
        if (minWritableBytes <= writableBytes()) {
            return;
        }

        int newCapacity;

        // 判断当前 ChannelBuffer 容量大小是否为 0
        if (capacity() == 0) {
            newCapacity = 1;
        } else {
            // 获取 ChannelBuffer 容量大小
            newCapacity = capacity();
        }

        // 计算预计容量大小
        int minNewCapacity = writerIndex() + minWritableBytes;

        // 如果预计容量大于当前 ChannelBuffer 的容量大小，则进行 2 倍容量扩容
        while (newCapacity < minNewCapacity) {
            newCapacity <<= 1;
        }

        // 通过工厂创建容量大小为 newCapacity 的 ChannelBuffer
        ChannelBuffer newBuffer = factory().getBuffer(newCapacity);

        // 将原来ChannelBuffer 中的数据拷贝到新的 ChannelBuffer 中
        newBuffer.writeBytes(buffer, 0, writerIndex());

        // 将 buffer 字段指向新 ChannelBuffer 对象
        buffer = newBuffer;
    }


    @Override
    public int capacity() {
        return buffer.capacity();
    }


    @Override
    public ChannelBuffer copy(int index, int length) {
        DynamicChannelBuffer copiedBuffer = new DynamicChannelBuffer(Math.max(length, 64), factory());
        copiedBuffer.buffer = buffer.copy(index, length);
        copiedBuffer.setIndex(0, length);
        return copiedBuffer;
    }


    @Override
    public ChannelBufferFactory factory() {
        return factory;
    }


    @Override
    public byte getByte(int index) {
        return buffer.getByte(index);
    }


    @Override
    public void getBytes(int index, byte[] dst, int dstIndex, int length) {
        buffer.getBytes(index, dst, dstIndex, length);
    }


    @Override
    public void getBytes(int index, ByteBuffer dst) {
        buffer.getBytes(index, dst);
    }


    @Override
    public void getBytes(int index, ChannelBuffer dst, int dstIndex, int length) {
        buffer.getBytes(index, dst, dstIndex, length);
    }


    @Override
    public void getBytes(int index, OutputStream dst, int length) throws IOException {
        buffer.getBytes(index, dst, length);
    }


    @Override
    public boolean isDirect() {
        return buffer.isDirect();
    }


    @Override
    public void setByte(int index, int value) {
        buffer.setByte(index, value);
    }


    @Override
    public void setBytes(int index, byte[] src, int srcIndex, int length) {
        buffer.setBytes(index, src, srcIndex, length);
    }


    @Override
    public void setBytes(int index, ByteBuffer src) {
        buffer.setBytes(index, src);
    }


    @Override
    public void setBytes(int index, ChannelBuffer src, int srcIndex, int length) {
        buffer.setBytes(index, src, srcIndex, length);
    }


    @Override
    public int setBytes(int index, InputStream src, int length) throws IOException {
        return buffer.setBytes(index, src, length);
    }


    @Override
    public ByteBuffer toByteBuffer(int index, int length) {
        return buffer.toByteBuffer(index, length);
    }

    @Override
    public void writeByte(int value) {
        ensureWritableBytes(1);
        super.writeByte(value);
    }

    @Override
    public void writeBytes(byte[] src, int srcIndex, int length) {
        ensureWritableBytes(length);
        super.writeBytes(src, srcIndex, length);
    }

    @Override
    public void writeBytes(ChannelBuffer src, int srcIndex, int length) {
        ensureWritableBytes(length);
        super.writeBytes(src, srcIndex, length);
    }

    @Override
    public void writeBytes(ByteBuffer src) {
        ensureWritableBytes(src.remaining());
        super.writeBytes(src);
    }

    @Override
    public int writeBytes(InputStream in, int length) throws IOException {
        ensureWritableBytes(length);
        return super.writeBytes(in, length);
    }


    @Override
    public byte[] array() {
        return buffer.array();
    }


    @Override
    public boolean hasArray() {
        return buffer.hasArray();
    }


    @Override
    public int arrayOffset() {
        return buffer.arrayOffset();
    }
}
