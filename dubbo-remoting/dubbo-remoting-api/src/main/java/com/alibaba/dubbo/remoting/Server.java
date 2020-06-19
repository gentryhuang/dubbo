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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.Resetable;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Remoting Server. (API/SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *
 * @see com.alibaba.dubbo.remoting.Transporter#bind(com.alibaba.dubbo.common.URL, ChannelHandler)
 * <p>
 * 服务器端口
 */
public interface Server extends Endpoint, Resetable {

    /**
     * is bound. 是否绑定本地端口，即是否启动成功，可连接，接收消息
     *
     * @return bound
     */
    boolean isBound();

    /**
     * get channels. 获取连接上服务器的通道列表 【客户端列表】
     *
     * @return channels
     */
    Collection<Channel> getChannels();

    /**
     * get channel. 根据地址获取连接上服务器的通道 【客户端】
     *
     * @param remoteAddress
     * @return channel
     */
    Channel getChannel(InetSocketAddress remoteAddress);

    /**
     * 重置，已废弃
     *
     * @param parameters
     */
    @Deprecated
    void reset(com.alibaba.dubbo.common.Parameters parameters);

}