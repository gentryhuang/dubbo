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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_SERVICE_REFERENCE_PATH;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_WARMUP;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_WEIGHT;
import static org.apache.dubbo.rpc.cluster.Constants.WARMUP_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WEIGHT_KEY;

/**
 * AbstractLoadBalance
 */
public abstract class AbstractLoadBalance implements LoadBalance {

    /**
     * @param invokers   invokers.
     * @param url        refer url
     * @param invocation invocation.
     * @param <T>
     * @return
     */
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 1 Invoker集合为空，直接返回null
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 2 Invoker集合只包含一个Invoker，则直接返回该Invoker对象
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        // 3 Invoker集合包含多个Invoker对象时，交给doSelect()方法处理，这是个抽象方法，留给子类具体实现
        return doSelect(invokers, url, invocation);
    }

    /**
     * 具体选择逻辑交给子类实现
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);


    /**
     * 获取权重
     * 说明：获取考虑了预热时间的调用者调用的权重。如果正常运行时间在预热时间内，则权重将按比例减少
     *
     * @param invoker    the invoker
     * @param invocation the invocation of this invoker
     * @return weight
     */
    int getWeight(Invoker<?> invoker, Invocation invocation) {
        int weight;
        URL url = invoker.getUrl();
        // 1 多注册中心场景，多注册中心负载均衡。
        if (REGISTRY_SERVICE_REFERENCE_PATH.equals(url.getServiceInterface())) {
            // 1.1 如果是RegistryService接口的话，直接根据配置项 registry.weight 获取权重即可，默认是 100
            weight = url.getParameter(REGISTRY_KEY + "." + WEIGHT_KEY, DEFAULT_WEIGHT);

            // 2 非多注册中心场景
        } else {
            // 2.1 从 url 中获取 weight 配置值，默认为 100
            weight = url.getMethodParameter(invocation.getMethodName(), WEIGHT_KEY, DEFAULT_WEIGHT);
            if (weight > 0) {
                // 2.2 获取服务提供者的启动时间戳
                long timestamp = invoker.getUrl().getParameter(TIMESTAMP_KEY, 0L);
                if (timestamp > 0L) {
                    // 2.3 计算Provider运行时长
                    long uptime = System.currentTimeMillis() - timestamp;
                    if (uptime < 0) {
                        return 1;
                    }
                    // 2.4 从 url 中获取 Provider 预热时间配置值，默认为10分钟
                    int warmup = invoker.getUrl().getParameter(WARMUP_KEY, DEFAULT_WARMUP);

                    // 2.5 如果Provider运行时间小于预热时间，则该Provider节点可能还在预热阶段，需要降低其权重
                    if (uptime > 0 && uptime < warmup) {
                        weight = calculateWarmupWeight((int) uptime, warmup, weight);
                    }
                }
            }
        }
        // 3 防御性编程，权重不能为负数
        return Math.max(weight, 0);
    }

    /**
     * 对还在预热状态的 Provider 节点进行降权，避免 Provider 一启动就有大量请求涌进来。
     *
     * @param uptime the uptime in milliseconds 服务运行时间
     * @param warmup the warmup time in milliseconds 预热时间
     * @param weight the weight of an invoker 配置的服务权重
     * @return weight which takes warmup into account 计算的服务权重
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        // 计算权重，简化为： (uptime/warmup) * weight。
        // 随着服务运行时间 uptime 增大，权重计算值 ww 会慢慢接近配置值 weight
        int ww = (int) (uptime / ((float) warmup / weight));
        // 权重范围为 [0,weight] 之间
        return ww < 1 ? 1 : (Math.min(ww, weight));
    }
}
