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
import java.io.OutputStream;

/**
 * 实现 OutputStream 接口
 */
public class ChannelBufferOutputStream extends OutputStream {

    /**
     * 被装饰的 ChannelBuffer
     */
    private final ChannelBuffer buffer;
    /**
     * 开始位置
     */
    private final int startIndex;

    public ChannelBufferOutputStream(ChannelBuffer buffer) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        // 设置 ChannelBuffer
        this.buffer = buffer;
        // 设置 开始位置 为 ChannelBuffer 的写入索引位置
        startIndex = buffer.writerIndex();
    }

    /**
     * 获取装饰的 ChannelBuffer
     *
     * @return
     */
    public ChannelBuffer buffer() {
        return buffer;
    }

    /**
     * 获取可以写入的量
     *
     * @return
     */
    public int writtenBytes() {
        return buffer.writerIndex() - startIndex;
    }

    //------------ 写入数据都是委托给被装饰的 ChannelBuffer -------------/

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }
        buffer.writeBytes(b, off, len);
    }

    @Override
    public void write(byte[] b) throws IOException {
        buffer.writeBytes(b);
    }

    @Override
    public void write(int b) throws IOException {
        buffer.writeByte((byte) b);
    }


}
