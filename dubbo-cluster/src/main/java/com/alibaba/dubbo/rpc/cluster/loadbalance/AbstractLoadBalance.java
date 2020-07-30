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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AbstractLoadBalance，实现LoadBalance 接口，LoadBalance 抽象类，提供了权重计算的功能。
 * 说明：
 *  服务预热是一个优化手段，与此类似的还有 JVM 预热。主要目的是让服务启动后“低功率”运行一段时间，使其效率慢慢提升至最佳状态。
 */
public abstract class AbstractLoadBalance implements LoadBalance {


    /**
     * 从Invoker集合中，选择一个
     *
     * @param invokers   invokers. Invoker 集合
     * @param url        refer url
     * @param invocation invocation.
     * @param <T>
     * @return
     */
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Invoker 为空直接返回null
        if (invokers == null || invokers.isEmpty()) {
            return null;
        }
        // Invoker只有一个时，直接选择返回
        if (invokers.size() == 1) {
            return invokers.get(0);
        }

        // 有多个Invoker时，需要具体子类实现选择逻辑
        return doSelect(invokers, url, invocation);
    }

    /**
     * 计算权重
     *
     * @param invoker
     * @param invocation
     * @return
     */
    protected int getWeight(Invoker<?> invoker, Invocation invocation) {

        // 获得weight 服务权重 配置项，默认为100
        int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT);

        if (weight > 0) {

            // 获取服务提供者启动时间戳
            long timestamp = invoker.getUrl().getParameter(Constants.REMOTE_TIMESTAMP_KEY, 0L);

            if (timestamp > 0L) {
                // 计算服务提供者运行时长 【当前时间戳 - 启动时间戳】
                int uptime = (int) (System.currentTimeMillis() - timestamp);

                // 获得服务预热配置项，默认为10分钟
                int warmup = invoker.getUrl().getParameter(Constants.WARMUP_KEY, Constants.DEFAULT_WARMUP);

                /**
                 * 如果服务运行时长处于预热时间内，则重新计算服务权重，降权处理。
                 * 说明：
                 * 对服务降权的目的是避免服务在启动之初就可能处于高负载状态
                 */
                if (uptime > 0 && uptime < warmup) {
                    // 计算服务权重
                    weight = calculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }

        return weight;
    }

    /**
     * 计算权重
     *
     * @param uptime
     * @param warmup
     * @param weight
     * @return
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        // 计算权重 【简化为： (uptime/warmup) * weight 】
        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));
        // 权重范围为 [0,weight] 之间
        return ww < 1 ? 1 : (ww > weight ? weight : ww);
    }

    /**
     * 子类实现，提供具体的负载均衡策略
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);

}
