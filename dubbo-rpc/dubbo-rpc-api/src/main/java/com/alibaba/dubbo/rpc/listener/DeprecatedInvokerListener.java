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
package com.alibaba.dubbo.rpc.listener;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;

/**
 * DeprecatedProtocolFilter  实现 InvokerListenerAdapter 抽象类 ，引用废弃的服务时，打印错误日志提醒
 *
 * @Activate(Constants.DEPRECATED_KEY) 注解，基于Dubbo SPI Activate 机制加载：
 * 1 配置方式：<dubbo:service interface="com.alibaba.dubbo.demo.DemoService" ref="demoService" deprecated="true" />，通过设置 deprecated为true，该方式仅适用于 远程引用服务
 *
 * 2 本地引用服务 废弃服务方式：
 *   <dubbo:reference id="demoService" interface="com.alibaba.dubbo.demo.DemoService" protocol="injvm">
 *     <dubbo:parameter key="deprecated" value="true" />
 *   </dubbo:reference>
 *
 *   注意：本地引用服务时，不是使用服务提供者的URL，而是服务消费者的URL
 *
 */
@Activate(Constants.DEPRECATED_KEY)
public class DeprecatedInvokerListener extends InvokerListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeprecatedInvokerListener.class);

    @Override
    public void referred(Invoker<?> invoker) throws RpcException {
        if (invoker.getUrl().getParameter(Constants.DEPRECATED_KEY, false)) {
            LOGGER.error("The service " + invoker.getInterface().getName() + " is DEPRECATED! Declare from " + invoker.getUrl());
        }
    }

}
