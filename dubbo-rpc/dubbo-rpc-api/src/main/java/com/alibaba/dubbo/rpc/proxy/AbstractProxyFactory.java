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
package com.alibaba.dubbo.rpc.proxy;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.service.EchoService;
import com.alibaba.dubbo.rpc.service.GenericService;

/**
 * AbstractProxyFactory // 代理对象工厂
 */
public abstract class AbstractProxyFactory implements ProxyFactory {

    @Override
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        // 调用重载方法
        return getProxy(invoker, false);
    }

    /**
     * 主要获取需要生成代理的接口列表 【这里回自动实现EchoService 接口】
     * <p>
     * 注意：这里会在原有Invoker对应关联的接口之上增加EchoService接口，作用是回声测试，每个服务都会自动实现EchoService接口。
     * 如果要使用回声测试，只需要将任意服务引用强制转型为EchoService即可使用： String status = echoService.$echo("OK");
     *
     * @param invoker
     * @param generic
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
        Class<?>[] interfaces = null;
        // 从Invoker的URL中获取接口列表
        String config = invoker.getUrl().getParameter("interfaces");
        if (config != null && config.length() > 0) {
            // 切分接口列表
            String[] types = Constants.COMMA_SPLIT_PATTERN.split(config);
            if (types != null && types.length > 0) {
                interfaces = new Class<?>[types.length + 2];
                // 设置服务接口类和EchoService.class【用于回声测试】到 interfaces 中。这是在原有Invoker对应关联的接口之上，增加EchoService接口
                interfaces[0] = invoker.getInterface();

                // 回声测试接口
                interfaces[1] = EchoService.class;
                for (int i = 0; i < types.length; i++) {
                    // 加载接口类
                    interfaces[i + 1] = ReflectUtils.forName(types[i]);
                }
            }
        }

        // 如果interfaces为空，增加EchoService接口，用于回声测试
        if (interfaces == null) {
            interfaces = new Class<?>[]{invoker.getInterface(), EchoService.class};
        }

        // 为http和hessian 协议提供泛化调用支持
        if (!invoker.getInterface().equals(GenericService.class) && generic) {
            int len = interfaces.length;
            Class<?>[] temp = interfaces;
            // 创建新的interfaces数组
            interfaces = new Class<?>[len + 1];
            System.arraycopy(temp, 0, interfaces, 0, len);
            // 设置GenericService.class 到数组中
            interfaces[len] = GenericService.class;
        }

        // 调用重载方法
        return getProxy(invoker, interfaces);
    }

    /**
     * 子类需要实现真正获取Proxy对象的逻辑
     *
     * @param invoker
     * @param types
     * @param <T>
     * @return
     */
    public abstract <T> T getProxy(Invoker<T> invoker, Class<?>[] types);

}
