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
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelEventRunnable;

/**
 * 实现 AbstractChannelHandlerDelegate 抽象类，解码处理器，专门处理 Decodeable 的 ChannelHandler实现，主要包含了一些解码逻辑。
 * 在Codec解码器实现中，如果请求体和响应结果需要在线程池中进行解码，那么就不进行直接解码，而是把解码任务交给线程池来做，就是这里 {@link com.alibaba.dubbo.rpc.protocol.dubbo.DubboCodec#decodeBody(com.alibaba.dubbo.remoting.Channel, java.io.InputStream, byte[])}
 * 说明：
 * 1 请求解码可在IO线程上执行，也可在线程池中执行，取决于配置
 * 2 DecodeHandler 存在的意义就是保证请求或响应对象可在线程池中被解码[该对象在线程池中工作{@link ChannelEventRunnable#handler}]
 * 3 解码完毕后，完全解码后的消息传递给底层的ChannelHandler对象继续处理
 */
public class DecodeHandler extends AbstractChannelHandlerDelegate {

    private static final Logger log = LoggerFactory.getLogger(DecodeHandler.class);

    public DecodeHandler(ChannelHandler handler) {
        super(handler);
    }

    /**
     * 覆写了 received(channel,message)方法
     *
     * @param channel
     * @param message RpcInvocation 或 RpcResult
     * @throws RemotingException
     * @see com.alibaba.dubbo.rpc.protocol.dubbo.DubboCodec#decodeBody(com.alibaba.dubbo.remoting.Channel, java.io.InputStream, byte[])
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 当消息是 Decodeable 类型时 进行解码
        if (message instanceof Decodeable) {
            decode(message);
        }

        // 当消息是Request类型时，对 data 字段进行解码
        if (message instanceof Request) {
            decode(((Request) message).getData());
        }

        // 当消息是Response类型时，对 result 字段进行解码
        if (message instanceof Response) {
            decode(((Response) message).getResult());
        }

        // 解码后，调用ChannelHandler#received(channel,message)方法，将消息交给委托的handler继续处理
        handler.received(channel, message);
    }

    /**
     * 解析消息
     *
     * @param message
     */
    private void decode(Object message) {
        /**
         * Decodeable 接口目前有两个实现类：
         * 1 DecodeableRpcInvocation
         * 2 DecodeableRpcResult
         */
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
