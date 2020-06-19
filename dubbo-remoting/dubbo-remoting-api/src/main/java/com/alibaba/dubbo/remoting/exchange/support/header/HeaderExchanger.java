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
package com.alibaba.dubbo.remoting.exchange.support.header;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Transporters;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Exchanger;
import com.alibaba.dubbo.remoting.transport.DecodeHandler;

/**
 * 实现 Exchanger 接口，基于消息头部( Header )的信息交换者实现类
 */
public class HeaderExchanger implements Exchanger {

    public static final String NAME = "header";

    /**
     * 通过 Transporters.connect(url,handler) 方法，创建通信Client，内嵌到 HeaderExchangeClient中
     *
     * @param url     服务器地址
     * @param handler 数据交换处理器
     * @return
     * @throws RemotingException
     */
    @Override
    public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        /**
         * 处理器顺序：DecodeHandler => HeaderExchangeHandler => ExchangeHandler【handler】
         */
        return new HeaderExchangeClient(Transporters.connect(url, new DecodeHandler(new HeaderExchangeHandler(handler))), true);
    }

    /**
     * 绑定服务器
     *
     * @param url     服务器地址
     * @param handler 数据交换处理器
     * @return
     * @throws RemotingException
     */
    @Override
    public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        /**
         * 1 对ExchangeHandlerAdapter 进行了两次包装，最终得到DecodeHandler
         * (1) 将ExchangeHandlerAdapter赋值给HeaderExchangeHandler的ExchangeHandler handler属性
         * (2) 将创建的 HeaderExchangeHandler 对象 赋值给 DecodeHandler的父类 AbstractChannelHandlerDelegate 的 ChannelHandler handler属性
         * 2 使用Transporters.bind创建服务，如NettyServer
         * 3 使用 HeaderExchangeServer 包装了上一步创建的服务
         */
        return new HeaderExchangeServer(Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler))));
    }

}
