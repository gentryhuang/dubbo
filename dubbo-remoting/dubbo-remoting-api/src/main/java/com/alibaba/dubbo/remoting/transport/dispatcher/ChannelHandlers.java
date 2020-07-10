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
package com.alibaba.dubbo.remoting.transport.dispatcher;


import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Dispatcher;
import com.alibaba.dubbo.remoting.exchange.support.header.HeartbeatHandler;
import com.alibaba.dubbo.remoting.transport.MultiMessageHandler;

/**
 * 通道处理器工厂
 */
public class ChannelHandlers {

    /**
     * 单例
     */
    private static ChannelHandlers INSTANCE = new ChannelHandlers();

    protected ChannelHandlers() {
    }

    /**
     * 无论是Client还是Server，都是类似的，将传入的ChannelHandler使用ChannelHandlers进行一次包装
     *
     * @param handler
     * @param url
     * @return
     */
    public static ChannelHandler wrap(ChannelHandler handler, URL url) {
        return ChannelHandlers.getInstance().wrapInternal(handler, url);
    }

    protected static ChannelHandlers getInstance() {
        return INSTANCE;
    }

    static void setTestingChannelHandlers(ChannelHandlers instance) {
        INSTANCE = instance;
    }

    /**
     * 说明：无论是请求还是响应都会按照这个顺序处理一遍
     * <p>
     * 1  对DecodeHandler对象进行层层包装，最终得到MultiMessageHandler：
     * MultiMessageHandler->HeartbeatHandler->AllChangeHandler[url:providerUrl,executor:FixedExecutor,handler:DecodeHandler] -> DecodeHandler -> HeaderExchangeHandler->ExchangeHandlerAdapter
     * 2 MultiMessageHandler 创建后，NettyServer就开始调用各个父类进行属性初始化
     *
     * @param handler
     * @param url
     * @return
     */
    protected ChannelHandler wrapInternal(ChannelHandler handler, URL url) {
        return new MultiMessageHandler( // MultiMessageHandler
                new HeartbeatHandler( // HeartbeatHandler
                        ExtensionLoader.getExtensionLoader(Dispatcher.class).getAdaptiveExtension() // AllDispatcher ,Dispatcher决定了dubbo的线程模型，指定了哪些线程做什么
                                .dispatch(handler, url) // AllChannelHandler
                )
        );
    }
}
