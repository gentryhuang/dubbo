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

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcInvocation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * InvokerInvocationHandler，实现了JDK的InvocationHandler
 */
public class InvokerInvocationHandler implements InvocationHandler {

    /**
     * Invoker对象，用于 #invoke方法调用
     */
    private final Invoker<?> invoker;

    public InvokerInvocationHandler(Invoker<?> handler) {
        this.invoker = handler;
    }

    /**
     * 代理对象【Proxy创建的】发出请求，会执行到这里。
     *
     * @param proxy
     * @param method
     * @param args
     * @return
     * @throws Throwable
     * @see com.alibaba.dubbo.rpc.proxy.javassist.JavassistProxyFactory#getProxy(com.alibaba.dubbo.rpc.Invoker, java.lang.Class[])
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        // 处理wait(),notify()等方法，进行反射调用
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(invoker, args);
        }

        // 基础方法，不使用RPC调用
        if ("toString".equals(methodName) && parameterTypes.length == 0) {
            return invoker.toString();
        }
        if ("hashCode".equals(methodName) && parameterTypes.length == 0) {
            return invoker.hashCode();
        }
        if ("equals".equals(methodName) && parameterTypes.length == 1) {
            return invoker.equals(args[0]);
        }
        /**
         * RPC调用
         * 1 new RpcInvocation(method,args),创建调用信息[方法名、方法参数类型、方法参数值、附加从参数以及Invoker]，在执行的过程中 Invocation 中的属性会有变动
         * 1.1 附加参数中的path：即接口名，将会用于服务端接收请求信息后从exportMap中选取Exporter实例，继而取出Exporter中的Invoker
         * 1.2 方法名，方法参数类型，方法参数值：将用于JavassistProxyFactory$AbstractProxyInvoker方法，即 调用 Wrapper 的 invokeMethod 方法，其内部会为每个ref的方法都做方法名和方法参数匹配校验，匹配直接调用调用
         * 2 recreate()方法，回放调用结果
         * 3 这里的invoker 是集群的Invoker
         */
        return invoker.invoke(new RpcInvocation(method, args)).recreate();
    }

}
