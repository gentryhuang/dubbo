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
package org.apache.dubbo.rpc.cluster.support.registry;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker;

import java.util.List;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.PREFERRED_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.*;

/**
 * When there're more than one registry for subscription.
 * <p>
 * This extension provides a strategy to decide how to distribute traffics among them:
 * 1. registry marked as 'preferred=true' has the highest priority.
 * 2. check the zone the current request belongs, pick the registry that has the same zone first.
 * 3. Evenly balance traffic between all registries based on each registry's weight.
 * 4. Pick anyone that's available.
 */
public class ZoneAwareClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(ZoneAwareClusterInvoker.class);

    public ZoneAwareClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {

        // -------------- 1 基于注册中心选择 Invoker ---------------

        // 1.1 优先找到 preferred 属性为 true 的注册中心，它是优先级最高的注册中心，只有该注册中心无可用 Provider 节点时，才会回落到其他注册中心
        for (Invoker<T> invoker : invokers) {
            // FIXME, the invoker is a cluster invoker representing one Registry, so it will automatically wrapped by MockClusterInvoker.
            MockClusterInvoker<T> mockClusterInvoker = (MockClusterInvoker<T>) invoker;
            if (mockClusterInvoker.isAvailable() && mockClusterInvoker.getRegistryUrl()
                    .getParameter(REGISTRY_KEY + "." + PREFERRED_KEY, false)) {
                return mockClusterInvoker.invoke(invocation);
            }
        }


        // 1.2 候选的 Invoker 都不在 preferred 属性为 true 的注册中心上，则根据请求中的 zone key 信息，查找该注册中下的 Invoker
        String zone = (String) invocation.getAttachment(REGISTRY_ZONE);
        if (StringUtils.isNotEmpty(zone)) {
            // 遍历候选的 Invoker 列表
            for (Invoker<T> invoker : invokers) {
                MockClusterInvoker<T> mockClusterInvoker = (MockClusterInvoker<T>) invoker;
                // 根据请求中的 registry_zone 做匹配，选择相同 zone 的注册中心下的 Invoker
                if (mockClusterInvoker.isAvailable() && zone.equals(mockClusterInvoker.getRegistryUrl().getParameter(REGISTRY_KEY + "." + ZONE_KEY))) {
                    return mockClusterInvoker.invoke(invocation);
                }
            }

            // 是否强制匹配
            String force = (String) invocation.getAttachment(REGISTRY_ZONE_FORCE);
            // 如果强制匹配，只匹配和请求中具有相同 zone 的注册中心下提供者，如果没有匹配到，则抛出异常。如果不是强制匹配，则使用负载均衡选择 Invoker
            if (StringUtils.isNotEmpty(force) && "true".equalsIgnoreCase(force)) {
                throw new IllegalStateException("No registry instance in zone or no available providers in the registry, zone: "
                        + zone
                        + ", registries: " + invokers.stream().map(invoker -> ((MockClusterInvoker<T>) invoker).getRegistryUrl().toString()).collect(Collectors.joining(",")));
            }
        }

        // ------------------ 2 根据负载均衡选择 Invoker  ------------------/
        Invoker<T> balancedInvoker = select(loadbalance, invocation, invokers, null);
        if (balancedInvoker.isAvailable()) {
            return balancedInvoker.invoke(invocation);
        }


        // ------------------ 3 以上两种都没有选中 Invoker ，则从候选 Invoker 列表中选择一个可用的即可 --------------/
        for (Invoker<T> invoker : invokers) {
            MockClusterInvoker<T> mockClusterInvoker = (MockClusterInvoker<T>) invoker;
            if (mockClusterInvoker.isAvailable()) {
                return mockClusterInvoker.invoke(invocation);
            }
        }
        throw new RpcException("No provider available in " + invokers);
    }

}
