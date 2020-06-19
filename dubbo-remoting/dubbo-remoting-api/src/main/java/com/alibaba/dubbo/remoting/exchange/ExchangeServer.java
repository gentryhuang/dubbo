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
package com.alibaba.dubbo.remoting.exchange;

import com.alibaba.dubbo.remoting.Server;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * ExchangeServer. (API/SPI, Prototype, ThreadSafe)
 * <p>
 * 继承Server 接口，信息交换服务接口
 */
public interface ExchangeServer extends Server {

    /**
     * 获得信息交换通道数组
     *
     * @return channels
     */
    Collection<ExchangeChannel> getExchangeChannels();

    /**
     * 根据地址获取信息交换通道
     *
     * @param remoteAddress
     * @return channel
     */
    ExchangeChannel getExchangeChannel(InetSocketAddress remoteAddress);

}