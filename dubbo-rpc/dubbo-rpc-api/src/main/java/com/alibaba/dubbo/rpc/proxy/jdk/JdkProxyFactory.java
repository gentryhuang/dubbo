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
package com.alibaba.dubbo.rpc.proxy.jdk;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.proxy.AbstractProxyFactory;
import com.alibaba.dubbo.rpc.proxy.AbstractProxyInvoker;
import com.alibaba.dubbo.rpc.proxy.InvokerInvocationHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * JdkProxyFactory，如果使用 JDK 生成代理，配置方式如下：
 * <ul>
 * <li>服务引用: <dubbo:reference proxy="jdk" /></li>
 * <li>服务暴露: <dubbo:service proxy="jdk" /></li>
 * </ul>
 */
public class JdkProxyFactory extends AbstractProxyFactory {

    /**
     * 调用 java.lang.reflect.Proxy#getProxy(ClassLoader loader, Class<?>[] interfaces, InvocationHandler h) 方法，创建 Proxy 对象。
     *
     * @param invoker
     * @param interfaces
     * @param <T>
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        return (T) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                interfaces,
                new InvokerInvocationHandler(invoker)
        );
    }

    /**
     * doInvoke方法调用是，直接通过反射，拿到实现类的Method，然后执行方法
     * @param proxy
     * @param type
     * @param url
     * @param <T>
     * @return
     */
    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {

        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                // 调用 Class#getMethod(String name, Class<?>... parameterTypes) 方法，反射获得方法。
                Method method = proxy.getClass().getMethod(methodName, parameterTypes);
                // 调用 Method#invoke(proxy, arguments) 方法，执行方法
                return method.invoke(proxy, arguments);
            }
        };
    }

}
