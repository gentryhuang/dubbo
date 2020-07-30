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
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.Random;

/**
 * LeastActiveLoadBalance，加权最小活跃数算法实现
 * 说明：
 * 最少活跃调用数的服务提供者优先选中，相同最小活跃数的根据权重随机。最小活跃数即最小连接数，服务提供者当前正在处理的请求数越少表示该服务提供者效率越高，
 * 此时应优先把请求分配给该服务提供者。
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    /**
     * 扩展点名
     */
    public static final String NAME = "leastactive";

    /**
     * 随机数生成器
     */
    private final Random random = new Random();

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Invoker的个数
        int length = invokers.size();
        // 最小活跃数
        int leastActive = -1;
        // 具有 相同最小活跃数 的Invoker数量
        int leastCount = 0;
        // 记录 具有相同最小活跃数的Invoker在Invoker列表中的下标 ，即leastIndexs 数组中如果有多个值，则说明有两个及以上的Invoker具有相同的活跃数【最小的】
        int[] leastIndexs = new int[length];
        // 总权重
        int totalWeight = 0;

        // 第一个最小活跃数的Invoker权重初始值，为了与其它具有相同最小活跃数的Invoker 进行权重比较
        int firstWeight = 0;

        // 是否所有具有相同最小活跃数的Invoker 的权重相同
        boolean sameWeight = true;

        // 遍历Invoker集合，计算 具有相同最小活跃数的Invoker下标的数组和个数 [可能是多个，也可能是一个]
        for (int i = 0; i < length; i++) {

            Invoker<T> invoker = invokers.get(i);

            // 当前Invoker的活跃数【调用中次数】
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();

            // 当前Invoker权重
            int afterWarmup = getWeight(invoker, invocation);

            // 发现更小的活跃数，则重新开始，重置相关属性值 【== -1 是第一次判断】，这个是必须要的，因为要的就是最小的活跃数，具有相同的最小活跃数只是一种复杂情况，需要根据权重再处理
            if (leastActive == -1 || active < leastActive) {
                // 使用当前活跃数更新最小的活跃数
                leastActive = active;
                // 具有相同最小活跃数的Invoker个数重置为1
                leastCount = 1;
                // 重新记录最小活跃数的Invoker 所在下标
                leastIndexs[0] = i;
                // 重置权重
                totalWeight = afterWarmup;
                // 重置第一个最小活跃数的Invoker权重
                firstWeight = afterWarmup;
                // 重置权重相同标识
                sameWeight = true;

                // 如果当前Invoker 的 活跃数等于最小活跃数
            } else if (active == leastActive) {
                // 记录当前Invoker所在的下标
                leastIndexs[leastCount++] = i;
                // 累计权重【针对的是具有相同的最小活跃数】
                totalWeight += afterWarmup;

                // 判断具有相同最小活跃数的invoker的权重是否都相同
                if (sameWeight && i > 0 && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }


        // 如果只有一个最小活跃数的Invoker，则直接返回
        if (leastCount == 1) {
            return invokers.get(leastIndexs[0]);
        }

        // 有多个具有最小活跃数的Invoker，但它们权重不相同，且总权重 > 0，则回到加权随机算法
        if (!sameWeight && totalWeight > 0) {
            // 基于总权重生成随机数，在[0,totalWeight+1)之间的数字
            int offsetWeight = random.nextInt(totalWeight) + 1;
            // 随机数落入哪个区域
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexs[i];

                // 随机数落入哪个区间，随机数 - 当前遍历的服务提供者的权重 是否 小于0 是关键
                offsetWeight -= getWeight(invokers.get(leastIndex), invocation);

                // todo 这里改为 < 更好，而不是 在生成随机数的时候 + 1
                if (offsetWeight <= 0) {
                    return invokers.get(leastIndex);
                }
            }
        }

        // 如果有多个 Invoker 具有相同的最小活跃数，但它们的权重相等，则均等随机
        return invokers.get(leastIndexs[random.nextInt(leastCount)]);
    }
}
