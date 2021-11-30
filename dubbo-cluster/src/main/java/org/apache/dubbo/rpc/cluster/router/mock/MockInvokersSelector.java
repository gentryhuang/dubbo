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
package org.apache.dubbo.rpc.cluster.router.mock;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.util.ArrayList;
import java.util.List;

import static org.apache.dubbo.rpc.cluster.Constants.INVOCATION_NEED_MOCK;
import static org.apache.dubbo.rpc.cluster.Constants.MOCK_PROTOCOL;

/**
 * A specific Router designed to realize mock feature.
 * If a request is configured to use mock, then this router guarantees that only the invokers with protocol MOCK appear in final the invoker list, all other invokers will be excluded.
 */
public class MockInvokersSelector extends AbstractRouter {
    public static final String NAME = "MOCK_ROUTER";
    private static final int MOCK_INVOKERS_DEFAULT_PRIORITY = Integer.MIN_VALUE;

    public MockInvokersSelector() {
        // 优先级
        this.priority = MOCK_INVOKERS_DEFAULT_PRIORITY;
    }

    /**
     * 对 Invoker 列表进行过滤
     *
     * @param invokers   invoker list
     * @param url        refer url
     * @param invocation invocation
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(final List<Invoker<T>> invokers,
                                      URL url, final Invocation invocation) throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }

        // 1 attachments 为 null，会过滤掉 MockInvoker，只返回普通的Invoker对象
        if (invocation.getObjectAttachments() == null) {
            return getNormalInvokers(invokers);

        } else {
            // 2 获取 invocation.need.mock 配置项
            String value = (String) invocation.getObjectAttachments().get(INVOCATION_NEED_MOCK);

            // 2.1 invocation.need.mock 参数值为 null 会过滤掉 MockInvoker，只返回普通的 Invoker 对象
            if (value == null) {
                return getNormalInvokers(invokers);

                // 2.2 invocation.need.mock为true，只返回 MockInvoker
            } else if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
                return getMockedInvokers(invokers);
            }
        }

        // 3 不匹配，直接返回 invokers
        return invokers;
    }

    /**
     * 获取 MockInvoker
     *
     * @param invokers
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> getMockedInvokers(final List<Invoker<T>> invokers) {
        // 不包含 MockInvoker 的情况下，直接返回 null
        if (!hasMockProviders(invokers)) {
            return null;
        }
        List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(1);
        for (Invoker<T> invoker : invokers) {
            // 根据 URL 的 protocol 进行过滤，只返回 protocol 为 mock 的 Invoker 对象
            // 一般情况就一个
            if (invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                sInvokers.add(invoker);
            }
        }
        return sInvokers;
    }

    /**
     * 获取普通的 Invoker
     *
     * @param invokers
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> getNormalInvokers(final List<Invoker<T>> invokers) {
        // 只要没有 MockInvoker ，就是普通 Invoker
        if (!hasMockProviders(invokers)) {
            return invokers;
        } else {
            List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(invokers.size());
            for (Invoker<T> invoker : invokers) {
                // 根据 URL 的 protocol 进行过滤，只返回 protocol 不为 mock 的 Invoker 对象
                if (!invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                    sInvokers.add(invoker);
                }
            }
            return sInvokers;
        }
    }

    /**
     * 通过 protocol = mock 来判断是否为 MockInvoker
     *
     * @param invokers
     * @param <T>
     * @return
     */
    private <T> boolean hasMockProviders(final List<Invoker<T>> invokers) {
        boolean hasMockProvider = false;
        for (Invoker<T> invoker : invokers) {
            // 判断是否存在 URL 的 protocol 为 mock 的 Invoker
            if (invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                hasMockProvider = true;
                break;
            }
        }
        return hasMockProvider;
    }

}
