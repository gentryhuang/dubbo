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
package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.support.MultiMessage;

/**
 * 实现 AbstractChannelHandlerDelegate 抽象类，多消息处理器，处理一次性接收到多条消息的情况。
 * 消息的处理交给包装的handler对象进行处理
 */
public class MultiMessageHandler extends AbstractChannelHandlerDelegate {

    public MultiMessageHandler(ChannelHandler handler) {
        super(handler);
    }

    /**
     * 覆写了 received方法
     *
     * @param channel
     * @param message
     * @throws RemotingException
     */
    @SuppressWarnings("unchecked")
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 消息类型是MultiMessage，即多消息
        if (message instanceof MultiMessage) {
            MultiMessage list = (MultiMessage) message;
            // 循环提交给handler处理
            for (Object obj : list) {
                handler.received(channel, obj);
            }
            // 如果是单消息时，直接提交给handler处理
        } else {
            handler.received(channel, message);
        }
    }
}
