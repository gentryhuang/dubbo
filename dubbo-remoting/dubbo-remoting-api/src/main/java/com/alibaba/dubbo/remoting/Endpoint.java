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

import com.alibaba.dubbo.common.URL;

import java.net.InetSocketAddress;

/**
 * Endpoint. (API/SPI, Prototype, ThreadSafe)
 *
 * @see com.alibaba.dubbo.remoting.Channel
 * @see com.alibaba.dubbo.remoting.Client
 * @see com.alibaba.dubbo.remoting.Server
 * <p>
 * 在 dubbo-remoting-api 中，一个 Client 或 Server，都可以看作是一个Endpoint。
 */
public interface Endpoint {

    /**
     * 关联的 URL 信息
     *
     * @return url
     */
    URL getUrl();

    /**
     * 底层 Channel 关联的 ChannelHandler
     *
     * @return channel handler
     */
    ChannelHandler getChannelHandler();

    /**
     * 获取本地地址
     *
     * @return local address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * 发送消息
     *
     * @param message
     * @throws RemotingException
     */
    void send(Object message) throws RemotingException;

    /**
     * 发送消息
     *
     * @param message
     * @param sent    true: 会等待消息发出，消息发送失败会抛出异常；  false: 不等待消息发出，将消息放入IO队列，即可返回
     */
    void send(Object message, boolean sent) throws RemotingException;

    /**
     * 关闭底层Channel
     */
    void close();

    /**
     * 优雅关闭底层Channel
     */
    void close(int timeout);

    /**
     * 开始关闭
     */
    void startClose();

    /**
     * 检测底层Channel是否已经关闭
     *
     * @return closed
     */
    boolean isClosed();

}