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
package com.alibaba.dubbo.rpc.cluster.router;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;

import java.util.ArrayList;
import java.util.List;

/**
 * 实现Router 接口，MockInvoker路由选择器实现类。
 * {@link AbstractDirectory#setRouters(java.util.List)} ,该路由会被设置并保存起来，每次Directory#list方法都会执行 MockInvokersSelector的路由逻辑
 * <p>
 * A specific Router designed to realize mock feature.
 * If a request is configured to use mock, then this router guarantees that only the invokers with protocol MOCK appear in final the invoker list, all other invokers will be excluded.
 */
public class MockInvokersSelector implements Router {

    /**
     * 根据 invocation.need.mock 来匹配对应类型的Invoker集合
     * 1 如果attachments 为空，普通Invoker集合
     * 2 若为true，MockInvoker 集合
     * 3 若为空，普通Invoker集合
     * 4 前两种没有匹配上，直接返回原Invoker集合，不处理
     *
     * @param invokers
     * @param url        refer url
     * @param invocation
     * @param <T>
     * @return 选出的可以被调用的RegistryDirectory$InvokerDelegete实例子集
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(final List<Invoker<T>> invokers, URL url, final Invocation invocation) throws RpcException {

        // 1 attachments 为 null，获取普通Invoker集合
        if (invocation.getAttachments() == null) {
            return getNormalInvokers(invokers);
        } else {
            // 获取 invocation.need.mock 的值
            String value = invocation.getAttachments().get(Constants.INVOCATION_NEED_MOCK);

            // 2 为null，获得普通Invoker集合
            if (value == null) {
                return getNormalInvokers(invokers);

                // 3 不为空且为true，获得MockInvoker集合
            } else if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
                return getMockedInvokers(invokers);
            }
        }

        // 4 返回原Invoker集合，不处理
        return invokers;
    }

    /**
     * 获取MockInvoker
     *
     * @param invokers
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> getMockedInvokers(final List<Invoker<T>> invokers) {

        // 如果不存在MockInvoker，直接返回null
        if (!hasMockProviders(invokers)) {
            return null;
        }

        List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(1);

        // 遍历Invoker集合，过滤掉普通Invoker，只取MockInvoker
        for (Invoker<T> invoker : invokers) {
            if (invoker.getUrl().getProtocol().equals(Constants.MOCK_PROTOCOL)) {
                sInvokers.add(invoker);
            }
        }
        return sInvokers;
    }

    /**
     * 对List<Invoker<T>>进行过滤，只取普通的Invoker
     *
     * @param invokers
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> getNormalInvokers(final List<Invoker<T>> invokers) {
        // 不存在MockInvoker时，直接返回 Invoker 集合
        if (!hasMockProviders(invokers)) {
            return invokers;

            // 存在MockInvoker的情况下，过滤掉MockInvoker
        } else {
            List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(invokers.size());

            // 遍历Invoker集合，过滤掉MockInvoker
            for (Invoker<T> invoker : invokers) {
                if (!invoker.getUrl().getProtocol().equals(Constants.MOCK_PROTOCOL)) {
                    sInvokers.add(invoker);
                }
            }
            return sInvokers;
        }
    }

    /**
     * 判断是否有 MockInvoker
     *
     * @param invokers
     * @param <T>
     * @return
     */
    private <T> boolean hasMockProviders(final List<Invoker<T>> invokers) {
        boolean hasMockProvider = false;
        // 遍历Invoker集合
        for (Invoker<T> invoker : invokers) {
            // 判断Invoker的URL的协议 是否为 mock，如果protocol = mock，则存在MockInvoker
            if (invoker.getUrl().getProtocol().equals(Constants.MOCK_PROTOCOL)) {
                hasMockProvider = true;
                break;
            }
        }
        return hasMockProvider;
    }

    @Override
    public URL getUrl() {
        return null;
    }

    @Override
    public int compareTo(Router o) {
        return 1;
    }

}
