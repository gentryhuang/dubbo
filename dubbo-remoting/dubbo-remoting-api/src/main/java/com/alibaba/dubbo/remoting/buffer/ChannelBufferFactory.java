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

import java.nio.ByteBuffer;

/**
 * 通道 Buffer 工厂接口
 */
public interface ChannelBufferFactory {

    /**
     * 获取指定容量的 ChannelBuffer
     * @param capacity
     * @return
     */
    ChannelBuffer getBuffer(int capacity);

    /**
     * 获取指定偏移量的数据的 ChannelBuffer
     * @param array
     * @param offset
     * @param length
     * @return
     */
    ChannelBuffer getBuffer(byte[] array, int offset, int length);

    /**
     * 根据 ByteBuffer 获取 ChannelBuffer
     * @param nioBuffer
     * @return
     */
    ChannelBuffer getBuffer(ByteBuffer nioBuffer);

}
