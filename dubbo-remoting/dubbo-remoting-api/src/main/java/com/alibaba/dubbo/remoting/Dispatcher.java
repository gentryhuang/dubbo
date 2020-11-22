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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.remoting.transport.dispatcher.all.AllDispatcher;

/**
 * ChannelHandlerWrapper (SPI, Singleton, ThreadSafe)
 * <span>说明：</span>
 * 1 调度器接口，被 @SPI(AllDispatcher.NAME)注解标注，是Dubbo 的拓展点，默认扩展名为 'all'
 * 2 如果事件处理的逻辑能迅速完成，并且不会发起新的 IO 请求，比如只是在内存中记个标识，则直接在 IO 线程上处理更快，因为减少了线程池调度。
 *   如果事件处理逻辑较慢，或者需要发起新的 IO 请求，比如需要查询数据库，则必须派发到线程池，否则 IO 线程阻塞，将导致不能接收其它请求。
 * 3 通过不同的派发策略和不同的线程池配置的组合来应对不同的场景。注意，派发策略和线程池的联系
 *
 * <span>在dubbo 中，有多种Dispatcher的实现</span>
 * <ul>
 *     <li>all: 所有消息都派发到线程池，包括请求，响应，连接事件，断开事件，心跳等</li>
 *     <li>direct: 所有消息都不派发到线程池，全部在IO线程上直接执行</li>
 *     <li>message: 只有请求/响应消息派发到线程池，其他的消息直接在IO线程上执行</li>
 *     <li>execution: 只有请求消息派发到线程池，其他的消息直接在IO线程上执行</li>
 *     <li>connection: 在IO线程上，将连接/断开事件放入队列，有序逐个执行。其他消息派发到线程池</li>
 * </ul>
 * 注意：每个Dispatcher实现类，都对应一个ChannelHandler实现类。默认情况下，使用AllDispatcher调度
 */
@SPI(AllDispatcher.NAME)
public interface Dispatcher {

    /**
     * 派发消息到线程池处理还是IO线程直接处理
     *
     * @param handler 通道处理
     * @param url     url
     * @return channel handler
     */
    @Adaptive({Constants.DISPATCHER_KEY, "dispather", "channel.handler"})
    ChannelHandler dispatch(ChannelHandler handler, URL url);

}