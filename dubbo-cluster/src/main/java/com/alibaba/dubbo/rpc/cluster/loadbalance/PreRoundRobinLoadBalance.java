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
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Round robin load balance，加权轮询，即根据权重对轮询过程进行干预，使得性能好的服务器可以得到更多的请求，性能差的得到的少一些。
 * 说明：
 * 1 2.6.5之前的版本
 * 2 RoundRobinLoadBalance 存在着比较严重的性能问题，问题出在了 Invoker 的返回时机上，RoundRobinLoadBalance 需要在mod == 0 && v.getValue() > 0 条件成立的情况下才会被返回相应的 Invoker。
 * 如果 mod 很大，比如 10000，50000，甚至更大时，doSelect 方法需要进行很多次计算才能将 mod 减为0。由此可知，doSelect 的效率与 mod 有关，时间复杂度为 O(mod)。mod 又受权重的影响，
 * 因此当某个服务提供者配置了非常大的权重，此时 RoundRobinLoadBalance 会产生比较严重的性能问题。
 */
public class PreRoundRobinLoadBalance extends AbstractLoadBalance {

    /**
     * 扩展点名称
     */
    public static final String NAME = "roundrobin";

    /**
     * 服务方法与服务调用编号计数器的映射
     * key: serviceKey + "." + methodName
     */
    private final ConcurrentMap<String, AtomicPositiveInteger> sequences = new ConcurrentHashMap<String, AtomicPositiveInteger>();

    /**
     *
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 完整方法名：serviceKey + "." + methodName
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();

        // Invoker的个数
        int length = invokers.size();

        // 最大权重
        int maxWeight = 0;

        // 最小权重
        int minWeight = Integer.MAX_VALUE;

        // Invoker权重映射  key: Invoker value: Invoker权重值
        final LinkedHashMap<Invoker<T>, IntegerWrapper> invokerToWeightMap = new LinkedHashMap<Invoker<T>, IntegerWrapper>();

        // 权重总合
        int weightSum = 0;

        // 遍历Invoker集合，计算最小，最大权重，以及总的权重
        for (int i = 0; i < length; i++) {

            // 当前Invoker权重
            int weight = getWeight(invokers.get(i), invocation);

            // 更新最大权重
            maxWeight = Math.max(maxWeight, weight);

            // 更新最小权重
            minWeight = Math.min(minWeight, weight);

            // 权重 > 0 的情况
            if (weight > 0) {
                // 设置 Invoker 权重映射
                invokerToWeightMap.put(invokers.get(i), new IntegerWrapper(weight));
                // 累加权重
                weightSum += weight;
            }
        }

        // 从缓存中获得服务方法对应的服务调用编号计数器，没有就创建。注意方法的构成： serviceKey + "." + methodName
        AtomicPositiveInteger sequence = sequences.get(key);
        if (sequence == null) {
            sequences.putIfAbsent(key, new AtomicPositiveInteger());
            sequence = sequences.get(key);
        }

        // 获取当前服务调用编号。注意，是先获取，然后再递增
        int currentSequence = sequence.getAndIncrement();

        // 权重不相等的情况，根据权重分配选择Invoker
        if (maxWeight > 0 && minWeight < maxWeight) {

            // 使用调用编号对权重总和进行取余操作
            int mod = currentSequence % weightSum;

            // 遍历 maxWeight 次
            for (int i = 0; i < maxWeight; i++) {

                // 循环Invoker权重映射集合
                for (Map.Entry<Invoker<T>, IntegerWrapper> each : invokerToWeightMap.entrySet()) {

                    // 获得Invoker
                    final Invoker<T> k = each.getKey();
                    // 获得Invoker 对应的 权重对象
                    final IntegerWrapper v = each.getValue();

                    /**
                     *  如果 mod == 0，且当前Invoker权重大于0，返回当前Invoker。
                     *  说明：
                     *   1 mod必须递减为0才会选出结果，这意味着mod 越大【最大为 weightSum - 1】那么Invoker越大的就可能被选中
                     *   2 核心点在 mod == 0 且 当前Invoker还有权重的时候，才返回当前的Invoker。即这意味着，mod在一定条件下初始值是固定的，在进行N次递减才会为0，那么Invoker的权重越大那么选中的可能就越大，
                     */
                    if (mod == 0 && v.getValue() > 0) {
                        return k;
                    }

                    //如果mod != 0 且 Invoker权重 > 0
                    if (v.getValue() > 0) {
                        // 递减当前Invoker权重
                        v.decrement();
                        // 递减mod
                        mod--;
                    }
                }

            }
        }

        // 服务提供者的权重都相等，此时通过简单轮询选择目标Invoker
        return invokers.get(currentSequence % length);
    }

    /**
     * Invoker 权重
     */
    private static final class IntegerWrapper {
        /**
         * Invoker 权重值
         */
        private int value;

        public IntegerWrapper(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }

        public void decrement() {
            this.value--;
        }
    }

}