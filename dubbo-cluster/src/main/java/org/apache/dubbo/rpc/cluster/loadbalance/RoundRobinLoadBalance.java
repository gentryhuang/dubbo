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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    /**
     * 扩展点名称
     */
    public static final String NAME = "roundrobin";
    /**
     * 长时间未更新的阈值 60 s
     */
    private static final int RECYCLE_PERIOD = 60000;

    /**
     * 每个 Invoker 对应的对象
     */
    protected static class WeightedRoundRobin {
        /**
         * 服务提供者配置权重，在负载均衡过程不会变化
         */
        private int weight;
        /**
         * 服务提供者当前权重，在负载均衡过程会动态调整，初始值为 0
         */
        private AtomicLong current = new AtomicLong(0);
        /**
         * 最后更新时间
         */
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }

        // Invoker当前权重 + 配置的权重
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }

        // Invoker当前权重 - 总权重
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    /**
     * key1: 服务键 + 方法名 -> 完整方法名
     * key2: URL串
     * value: WeightedRoundRobin
     * 存储结构：
     * {
     * "UserService.query":{
     * "url1": WeightedRoundRobin@123,
     * "url2": WeightedRoundRobin@456
     * },
     * "UserService.update":{
     * "url1": WeightedRoundRobin@111,
     * "url2": WeightedRoundRobin@222
     * }
     * }
     */
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     *
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 1 获取请求的完成方法名
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();

        // 2 获取整个Invoker列表对应的 WeightedRoundRobin 映射表，如果为空，则创建一个新的WeightedRoundRobin映射表
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

        // 总权重
        int totalWeight = 0;
        // 记录 Invoker 列表中最大权重
        long maxCurrent = Long.MIN_VALUE;
        // 获取当前时间戳
        long now = System.currentTimeMillis();
        // 选中的 Invoker
        Invoker<T> selectedInvoker = null;
        // 选中的 Invoker 对应的 WeightedRoundRobin
        WeightedRoundRobin selectedWRR = null;

        // 3 遍历 Invoker 列表，选出具有最大 current 的 Invoker
        for (Invoker<T> invoker : invokers) {
            // 获取 Invoker 对应的URL串
            String identifyString = invoker.getUrl().toIdentityString();
            // 获取当前 Invoker 权重
            int weight = getWeight(invoker, invocation);
            // 检测当前 Invoker 是否有相应的 WeightedRoundRobin ，没有则创建
            WeightedRoundRobin weightedRoundRobin = map.computeIfAbsent(identifyString, k -> {
                WeightedRoundRobin wrr = new WeightedRoundRobin();
                // 设置权重和初始化当前权重值为0
                wrr.setWeight(weight);
                return wrr;
            });

            // 检测 Invoker 权重是否发生了变化，若变化了则更新相应 WeightedRoundRobin 中的 weight 值
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }

            // 3.1 让 current 加上配置的 weight 🌟
            long cur = weightedRoundRobin.increaseCurrent();
            // 3.2 更新 lastUpdate 字段
            weightedRoundRobin.setLastUpdate(now);

            // 3.3 寻找具有最大 current 的 Invoker，以及Invoker对应的 WeightedRoundRobin ，暂存起来留作后用
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }

            // 3.4 计算权重总和
            totalWeight += weight;
        }

        // 4 Invoker 集合数不等于缓存数，说明存在 Invoker 挂了的可能，此时应该清除无效缓存
        if (invokers.size() != map.size()) {
            // 清除掉长时间未被更新的节点
            map.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
        }

        // 5 更新选中的 Invoker 对应的 WeightedRoundRobin 中维护的 current 的值，然后返回选中的 Invoker 🌟
        if (selectedInvoker != null) {
            // 用 current 减去 totalWeight
            selectedWRR.sel(totalWeight);
            // 返回选中的Invoker对象
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
