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

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;

/**
 * 实现 AbstractChannelHandlerDelegate 抽象类，解码处理器，处理接收到的消息
 */
public class DecodeHandler extends AbstractChannelHandlerDelegate {

    private static final Logger log = LoggerFactory.getLogger(DecodeHandler.class);

    public DecodeHandler(ChannelHandler handler) {
        super(handler);
    }

    /**
     * 覆写了 received(channel,message)方法
     * @param channel
     * @param message
     * @throws RemotingException
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 当消息是 Decodeable 类型时
        if (message instanceof Decodeable) {
            decode(message);
        }

        // 当消息是Request类型时
        if (message instanceof Request) {
            decode(((Request) message).getData());
        }

        // 当消息是Response类型时
        if (message instanceof Response) {
            decode(((Response) message).getResult());
        }

        // 调用ChannelHandler#received(channel,message)方法，将消息交给委托的handler继续处理
        handler.received(channel, message);
    }

    /**
     * 解析消息
     * @param message
     */
    private void decode(Object message) {
        // 当类型是Decodeable时，调用 Decodeable#decode方法进行解析
        if (message != null && message instanceof Decodeable) {
            try {
                // 解析消息
                ((Decodeable) message).decode();
                if (log.isDebugEnabled()) {
                    log.debug("Decode decodeable message " + message.getClass().getName());
                }
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Call Decodeable.decode failed: " + e.getMessage(), e);
                }
            } // ~ end of catch
        } // ~ end of if
    } // ~ end of method decode

}
