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
 * ShortestResponseLoadBalance
 * </p>
 * Filter the number of invokers with the shortest response time of success calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 */
public class ShortestResponseLoadBalance extends AbstractLoadBalance {
    /**
     * 扩展名
     */
    public static final String NAME = "shortestresponse";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        // -------------------------- 1 🪐 关键属性 ------------------------/

        // 记录 Invoker 集合数量
        int length = invokers.size();

        // 记录所有 Invoker 集合中最短响应时间
        long shortestResponse = Long.MAX_VALUE;

        // 记录具有相同最短响应时间（shortestResponse 的值）的 Invoker 数量
        int shortestCount = 0;

        // 存放具有相同最短响应时间（shortestResponse 的值）的 Invoker 在 Invoker 列表中的下标
        // shortestIndexes 数组中如果有多个值，则说明有两个及以上的 Invoker 具有相同的最短响应时间
        int[] shortestIndexes = new int[length];

        // 存放每个 Invoker 权重，主要用于当最短响应时间的 Invoker 数量有多个的情况
        int[] weights = new int[length];

        // 记录具有相同最短响应时间 Invoker 的总权重
        int totalWeight = 0;

        // 记录第一个 Invoker 对象的权重
        int firstWeight = 0;

        // 标记是否具有相同权重的最短响应时间的 Invoker
        boolean sameWeight = true;

        // --------------------------- 2 🪐 操作关键属性 -----------------------/

        // 遍历所有 Invoker ，选出最短响应时间的 Invoker 集合
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);

            // 使用到了消费方限流策略：ActiveLimitFilter
            RpcStatus rpcStatus = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());

            // 获取调用成功的平均时间，计算方式：调用成功的请求数总数对应的总耗时 / 调用成功的请求数总数 = 成功调用的平均时间
            long succeededAverageElapsed = rpcStatus.getSucceededAverageElapsed();
            // 获取该提供者的活跃请求数，也就是当前正在处理中的请求数
            int active = rpcStatus.getActive();

            // 计算一个处理新请求的预估值，也就是如果当前请求发给该提供者，大概耗时多久处理完成
            long estimateResponse = succeededAverageElapsed * active;

            // 获取该 Invoker 的权重
            int afterWarmup = getWeight(invoker, invocation);
            weights[i] = afterWarmup;

            // 和 LeastActiveLoadBalance 类似
            // 比较最短时间，发现更小值则更新相关属性，这种情况只有一个 Invoker
            if (estimateResponse < shortestResponse) {
                // 重新记录最短响应时间
                shortestResponse = estimateResponse;
                // 重新记录最短响应时间的 Invoker 数量
                shortestCount = 1;
                // 重新记录最短响应时间的 Invoker 在 Invoker 列表中的下标
                shortestIndexes[0] = i;

                // 重置总权重
                totalWeight = afterWarmup;

                // 记录第一个最短响应时间的 Invoker 的权重
                firstWeight = afterWarmup;

                // 重置权重相同标识
                sameWeight = true;

                // 出现多个耗时最短的Invoker对象
            } else if (estimateResponse == shortestResponse) {
                // 记录当前 Invoker 在 Invoker 列表中的下标
                shortestIndexes[shortestCount++] = i;
                // 累加总权重，针对的是具有相同的最短响应时间
                totalWeight += afterWarmup;

                // 判断是否存在相同权重的最短响应时间的 Invoker
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }

        //------------------------------ 3 🪐 选择 Invoker ----------------------/

        // 仅有一个最短响应时间的 Invoker
        if (shortestCount == 1) {
            return invokers.get(shortestIndexes[0]);
        }

        // 如果耗时最短的所有Invoker对象的权重不相同，则通过加权随机负载均衡的方式选择一个Invoker返回
        if (!sameWeight && totalWeight > 0) {
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            for (int i = 0; i < shortestCount; i++) {
                int shortestIndex = shortestIndexes[i];
                offsetWeight -= weights[shortestIndex];
                if (offsetWeight < 0) {
                    return invokers.get(shortestIndex);
                }
            }
        }

        // 如果耗时最短的所有 Invoker 对象的权重相同，则随机返回一个
        return invokers.get(shortestIndexes[ThreadLocalRandom.current().nextInt(shortestCount)]);
    }
}
