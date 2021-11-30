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
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {
    /**
     * 扩展点名
     */
    public static final String NAME = "leastactive";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // ---------------------- 1 🌟 关键属性 ------------------------------/

        // Invoker 数量
        int length = invokers.size();

        // 记录最小的活跃调用数
        int leastActive = -1;

        // 记录具有相同最小活跃调用数（leastActive 的值）的 Invoker 数量
        int leastCount = 0;

        // 记录具有相同最小活跃调用数（leastActive 的值）的 Invoker 在 Invoker 列表中的下标位置。
        // leastIndexes 数组中如果有多个值，则说明有两个及以上的 Invoker 具有相同的最小活跃数（leastActive 的值）
        int[] leastIndexes = new int[length];

        // 记录每个 Invoker 的权重值
        int[] weights = new int[length];

        // 记录最小活跃调用数所有 Invoker 的权重值之和
        int totalWeight = 0;

        // 记录最小活跃请求数 Invoker 集合中第一个 Invoker 的权重值
        int firstWeight = 0;

        // 标记是否具有相同权重的最小活跃数 Invoker
        boolean sameWeight = true;

        // ---------------------- 2 🌟 操作关键属性 ------------------------------/

        // 遍历所有Invoker，选出最小活跃调用数的Invoker集合
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);

            // 获取该 Invoker 的活跃调用数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();

            // 记录该 Invoker 的权重
            int afterWarmup = getWeight(invoker, invocation);
            weights[i] = afterWarmup;

            // 比较活跃调用数，发现更小的活跃调用数则更新相关属性。这样情况只有一个 Invoker
            // 这个是必须要的，因为要的就是最小活跃调用数，具有相同的最小活跃调用数只是一种复杂情况，需要根据权重再处理
            if (leastActive == -1 || active < leastActive) {
                // 重新记录最小的活跃调用数
                leastActive = active;
                // 重新记录最小活跃调用数的 Invoker 个数
                leastCount = 1;

                // 重新记录最小活跃调用数的 Invoker 在 Invoker 列表中的下标
                leastIndexes[0] = i;

                // 重置总权重
                totalWeight = afterWarmup;

                // 记录第一个最小活跃调用数 Invoker 的权重
                firstWeight = afterWarmup;

                // Each invoke has the same weight (only one invoker here)
                // 重置权重相同标识
                sameWeight = true;

                // 如果当前 Invoker 的活跃调用数等于最小活跃调用数，这样情况下已经存在最小活跃调用数的 Invoker
            } else if (active == leastActive) {

                // 记录当前 Invoker 在 Invoker 列表中的下标
                leastIndexes[leastCount++] = i;

                // 累加总权重，针对的是具有相同的最小活跃数
                totalWeight += afterWarmup;

                // 判断是否存在相同权重的最小活跃调用数的 Invoker
                // 即检测当前 Invoker 的权重与firstWeight是否相等，不相等则将 sameWeight 设置为 false
                if (sameWeight && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }

        // ---------------------- 3 🌟 选择 Invoker ------------------------------/

        // 3.1 如果只有一个最小活跃调用数的 Invoker ，直接取出即可
        if (leastCount == 1) {
            // 从 Invoker 列表中取出最小活跃数的 Invoker
            return invokers.get(leastIndexes[0]);
        }

        // 3.2 存在多个具有最小活跃数的 Invoker ，但它们的权重不相同且总权重 > 0 ，则使用加权随机算法。
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on 
            // totalWeight.
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }

        // 3.3 存在多个 Invoker 具有相同的最小活跃数，但它们的权重相等或总权重为0，则使用随机均等
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}
