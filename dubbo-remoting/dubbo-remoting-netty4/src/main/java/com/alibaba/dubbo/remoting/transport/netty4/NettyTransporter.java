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
package com.alibaba.dubbo.remoting.transport.netty4;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Client;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.Transporter;

/**
 * 实现 Transporter接口，基于Netty4 的网络传输实现类
 * 说明：
 * NettyTransporter 基于 Dubbo SPI 机制加载，创建 NettyServer 和 NettyClient 对象。
 */
public class NettyTransporter implements Transporter {

    /**
     * 拓展名
     */
    public static final String NAME = "netty4";

    /**
     * 绑定一个服务器
     *
     * @param url      服务器地址
     * @param listener 通道处理器
     * @return server 返回服务器
     * @throws RemotingException
     */
    @Override
    public Server bind(URL url, ChannelHandler listener) throws RemotingException {
        return new NettyServer(url, listener);
    }

    /**
     * 连接一个服务器，即创建一个客户端
     *
     * @param url      服务器地址
     * @param listener 通道处理器
     * @return client 客户端
     * @throws RemotingException
     */
    @Override
    public Client connect(URL url, ChannelHandler listener) throws RemotingException {
        return new NettyClient(url, listener);
    }

}
