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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.List;
import java.util.Random;

/**
 * random load balance，加权随机算法的实现
 * 说明：
 * 本质上就是每个服务提供者的权重占总权重的比例，比例越大就越有可能选中
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    /**
     * 扩展点名称
     */
    public static final String NAME = "random";

    /**
     * 随机数生成器
     */
    private final Random random = new Random();

    /**
     * 随机负载均衡器 选择 Invoker
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        // Invoker的个数
        int length = invokers.size();

        // 算有的Invoker权重总合
        int totalWeight = 0;

        // 每个Invoker是否具有相同的权重
        boolean sameWeight = true;

        // 计算总权重，并检测每个服务提供者的权重是否相同
        for (int i = 0; i < length; i++) {

            // 获取当前Invoker的权重
            int weight = getWeight(invokers.get(i), invocation);

            // 计算权重总
            totalWeight += weight;

            // 判断每个服务提供者是否具有相同的权重【每一次都会和前一个服务提供者的权重相比】
            if (sameWeight && i > 0 && weight != getWeight(invokers.get(i - 1), invocation)) {
                sameWeight = false;
            }
        }

        // 权重不相等，获取随机数，并计算随机数落在哪个区间上
        if (totalWeight > 0 && !sameWeight) {
            // 基于总权重生成一个随机数，[0,totalWeight]之间的数
            int offset = random.nextInt(totalWeight);

            // 随机数落入哪个区间，随机数 - 当前遍历的服务提供者的权重 是否 小于0 是关键
            for (int i = 0; i < length; i++) {
                offset -= getWeight(invokers.get(i), invocation);
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }

        // 如果所有服务提供者权重相同或权重为0则均等随机
        return invokers.get(random.nextInt(length));
    }

}
