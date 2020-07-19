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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

/**
 * ClassLoaderInvokerFilter，类加载器切换过滤器
 * 说明：
 *   切换到加载了接口定义的类加载器，以便实现与相同的类加载器上下文一起工作，即：
 *   如果要改变默认情况下的双亲委派模型查找Class,通常会使用上下文类加载器ContextClassLoader。当前框架线程的类加载可能和服务接口的类加载器不是同一个，
 *   但是当前框架线程需要获取服务接口的类加载器中相关的Class，为了避免出现ClassNotFoundException，就可以使用上下文类加载器保存服务接口的类加载器，这样
 *   就可以获取服务接口的类加载器了，进而获得这个类加载器中的所需Class。
 * 例子：
 *   DubboProtocol#optimizeSerialization方法中，需要根据服务配置的序列化类名获取对应的自定义序列处理类，这些外部引入的序列化类在框架的类加载器中
 *   是没有的，因此需要使用Invoker的类（服务接口类）加载器获取目标类。
 *
 */
@Activate(group = Constants.PROVIDER, order = -30000)
public class ClassLoaderFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得当前线程对应的上下文类加载器
        ClassLoader ocl = Thread.currentThread().getContextClassLoader();
        // 切换当前线程的上下文类加载器为服务接口的类加载器
        Thread.currentThread().setContextClassLoader(invoker.getInterface().getClassLoader());
        try {
            // 放行，继续过滤器链的下一个节点
            return invoker.invoke(invocation);
        } finally {
            // 切换当前线程的上下文类加载器为原来的类加载器
            Thread.currentThread().setContextClassLoader(ocl);
        }
    }

}
