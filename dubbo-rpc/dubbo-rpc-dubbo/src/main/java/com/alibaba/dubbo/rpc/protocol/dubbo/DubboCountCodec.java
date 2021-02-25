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

package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.MultiMessage;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;

import java.io.IOException;

/**
 * 实现Codec2接口，支持多消息的编解码器
 * 注意：
 * 1  在DubboProtocol中 Client和Server 创建的过程，设置了编码器，默认拓展名为 'dubbo'，通过Dubbo SPI机制加载到的就是 DubboCountCodec
 * 2 DubboCountCodec 只负责在解码过程中 ChannelBuffer 的 readerIndex 指针控制
 */
public final class DubboCountCodec implements Codec2 {

    /**
     * Dubbo的编解码器
     */
    private DubboCodec codec = new DubboCodec();

    /**
     * 编码，直接委托给 DubboCodec 处理
     *
     * @param channel
     * @param buffer
     * @param msg
     * @throws IOException
     */
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        codec.encode(channel, buffer, msg);
    }

    /**
     * 解码，
     *
     * @param channel
     * @param buffer
     * @return
     * @throws IOException
     */
    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 记录当前读位置，用于下面计算每条消息的长度
        int save = buffer.readerIndex();

        // 创建MultiMessage 对象【多消息的封装】。MultiMessageHandler支持对它的处理分发
        MultiMessage result = MultiMessage.create();

        // 循环解码消息
        do {

            // 通过DubboCodec提供的解码能力解码一条消息
            Object obj = codec.decode(channel, buffer);

            // 字节数不够，重置读指针，然后结束解析
            if (Codec2.DecodeResult.NEED_MORE_INPUT == obj) {
                buffer.readerIndex(save);
                break;

                // 将成功解码的消息添加到MultiMessage中暂存
            } else {
                // 添加结果消息
                result.addMessage(obj);
                // 记录消息长度到隐式参数集合，用于 MonitorFilter 监控
                logMessageLength(obj, buffer.readerIndex() - save);
                // 记录当前读位置，用于计算下一条消息的长度
                save = buffer.readerIndex();
            }
        } while (true);

        // 一条消息也未解码出来，则返回NEED_MORE_INPUT错误码
        if (result.isEmpty()) {
            return Codec2.DecodeResult.NEED_MORE_INPUT;
        }

        // 只解码出来一条消息，则直接返回该条消息
        if (result.size() == 1) {
            return result.get(0);
        }

        // 解码出多条消息的话，会将MultiMessage返回
        return result;
    }

    private void logMessageLength(Object result, int bytes) {
        if (bytes <= 0) {
            return;
        }
        if (result instanceof Request) {
            try {
                ((RpcInvocation) ((Request) result).getData()).setAttachment(
                        Constants.INPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        } else if (result instanceof Response) {
            try {
                ((RpcResult) ((Response) result).getResult()).setAttachment(
                        Constants.OUTPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        }
    }

}
