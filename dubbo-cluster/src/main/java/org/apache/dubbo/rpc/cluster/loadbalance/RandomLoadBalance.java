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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This class select one provider from multiple providers randomly.
 * You can define weights for each provider:
 * If the weights are all the same then it will use random.nextInt(number of invokers).
 * If the weights are different then it will use random.nextInt(w1 + w2 + ... + wn)
 * Note that if the performance of the machine is better than others, you can set a larger weight.
 * If the performance is not so good, you can set a smaller weight.
 */
public class RandomLoadBalance extends AbstractLoadBalance {
    /**
     * 扩展点名称
     */
    public static final String NAME = "random";

    /**
     * Select one invoker between a list using a random criteria
     *
     * @param invokers   List of possible invokers
     * @param url        URL
     * @param invocation Invocation
     * @param <T>
     * @return The selected invoker
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size();
        // 每个 Invoker 权重是否相同的标志
        boolean sameWeight = true;
        // 计算每个 Invoker 对象对应的权重，并填充到 weights 数组中
        int[] weights = new int[length];


        // 计算第一个 Invoker 权重
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;

        // 记录权重总和
        int totalWeight = firstWeight;
        for (int i = 1; i < length; i++) {
            // 计算第 i 个 Invoker 的权重
            int weight = getWeight(invokers.get(i), invocation);
            weights[i] = weight;
            // 累加总权重
            totalWeight += weight;

            // 检测是否有不同权重的 Invoker
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }

        // 总权重 > 0 && 并非所有 Invoker 权重都相同
        // 计算随机数落在哪个区间
        if (totalWeight > 0 && !sameWeight) {
            // 随机获取一个 [0,totalWeight) 区间内的随机数
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);

            // 循环让随机数数减去Invoker的权重值，当随机数小于0时，返回相应的Invoker
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }

        // 如果所有的 Invoker 权重相同 或 权重总权重为 0，则均等随机
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
