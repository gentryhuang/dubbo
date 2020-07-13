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
package com.alibaba.dubbo.rpc.service;

import com.alibaba.dubbo.rpc.filter.EchoFilter;
import com.alibaba.dubbo.rpc.proxy.AbstractProxyFactory;

/**
 * 回声检测接口，所有消费服务自动实现EchoService 接口
 * <p>
 * 1 服务提供者是不实现EchoService 接口，而是通过 EchoFilter 实现 {@link EchoFilter#invoke(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)}
 * 2 在服务引用时只需将服务引用强制转为EchoService 即可使用。原理 {@link AbstractProxyFactory#getProxy(com.alibaba.dubbo.rpc.Invoker, boolean)}
 * 如： EchoService echoService = (EchoService)demoService; String status = echoService.$echo("OK");
 * <p>
 * Echo service.
 *
 * @export
 */
public interface EchoService {

    /**
     * echo test.
     *
     * @param message message.
     * @return message.
     */
    Object $echo(Object message);

}