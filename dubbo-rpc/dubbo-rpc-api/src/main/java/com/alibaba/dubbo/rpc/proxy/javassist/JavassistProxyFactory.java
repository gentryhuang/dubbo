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
package com.alibaba.dubbo.rpc.proxy.javassist;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.bytecode.Proxy;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.proxy.AbstractProxyFactory;
import com.alibaba.dubbo.rpc.proxy.AbstractProxyInvoker;
import com.alibaba.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * JavaassistRpcProxyFactory
 */
public class JavassistProxyFactory extends AbstractProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        /**
         * 1 生成Proxy子类（Proxy是抽象类），并调用Proxy子类的newInterface方法生成Proxy实例并初始化了其中的InvocationHandler，handler对象为InvokerInvocationHandler
         * 2 生成的proxy类会实现传入进来的接口
         * 3 InvokerInvocationHandler实现自JDK的InvocationHandler接口，具体的用途就是拦截接口类方法调用。
         * 3.1 Proxy.getProxy(interfaces)方法，生成Proxy实现Proxy抽象，是创建生成proxy的工厂（一一对应），如：Proxy0创建proxy0对象
         * 3.2 Proxy.newInstance(InvocationHandler)方法，创建proxy对象。其中传入的参数是InvokerInvocationHandler类，通过这种方式让proxy和真正的逻辑代码解耦:
         * client ==> proxy.method -> invokerInvocationHandler.invoke -> invoker.method
         *
         * 4 如果服务消费方使用了Stub的话，会先执行Stub的逻辑，然后再执行 3 中逻辑
         */
        return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // Wrapper类不能正确处理类名包含$的类
        /**
         * JavassistProxyFactory 模式原理：
         * 1 为目标类创建Wrapper类，实现invokeMethod方法。该类记录了实例的属性名，方法名等信息.
         * 2 在方法invokeMethod体中会为每个ref的方法都做方法名和方法参数匹配校验，匹配直接调用调用。相比较JdkProxyFactory省去了反射调用的开销【JdkProxyFactory通过反射
         * 获取真实对象的方法，然后调用】
         * 3 Invoker被调用的时候会触发doInvoke方法，然后调用Wrapper的invokeMethod方法。Wrapper的实现类是通过Javassist技术实现的
         * 4 todo 注意：消费方在调用该Invoker的方法时， Wrapper实现的invokeMethod方法做了一次转发，然后才会真正调用Invoker【AbstractProxyInvoker】中的ref的方法【Invoker封装了ref】
         * 5 一个Wrapper类，只对应一个Service
         */
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        /**
         *
         * 创建 继承自AbstractProxyInvoker类的匿名对象，并实现 doInvoke方法：将调用请求转发给了 Wrapper 类的 invokeMethod 方法，从而调用Service的方法
         */
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                // 调用 Wrapper 的 invokeMethod 方法，invokeMethod 最终会调用目标方法
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

}
