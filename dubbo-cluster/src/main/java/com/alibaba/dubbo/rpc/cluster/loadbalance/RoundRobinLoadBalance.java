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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.  加权轮循
 * <p>
 * Smoothly round robin's implementation @since 2.6.5
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    /**
     * 扩展点名称
     */
    public static final String NAME = "roundrobin";

    /**
     * 长时间未更新的阈值 60秒
     */
    private static int RECYCLE_PERIOD = 60000;

    /**
     * 权重轮循对象
     * 说明：
     * 每个服务提供者对应两个权重，分别为 weight 和 currentWeight。其中 weight 是固定的，currentWeight 是会动态调整，初始值为0。
     */
    protected static class WeightedRoundRobin {
        /**
         * 服务提供者权重
         */
        private int weight;
        /**
         * 当前权重，该权重是动态变化。 它是核心属性。
         */
        private AtomicLong current = new AtomicLong(0);
        /**
         * 最后一次更新时间
         */
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }

        public long increaseCurrent() {
            return current.addAndGet(weight);
        }

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
     * 完整方法名到权重的映射
     * <p>
     * key: 服务类名 + 方法名
     * value:  url唯一标识identifyString 到WeightedRoundRobin 映射
     */
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    /**
     * 原子更新锁
     */
    private AtomicBoolean updateLock = new AtomicBoolean();

    /**
     * 选择目标Invoker
     * 说明：
     * 1 这里多个字段都是 "当前"，是因为该算法是加权轮循，主要围绕着Invoker的当前权重进行，这个当前权重会进行累加Invoker的权重或者减去总权重，是动态变化的
     * 2 找到目标Invoker的核心是，先遍历Invoker集合找出最大的当前权重maxCurrent【是目标Invoker的当前权重current属性值】，它对应的Invoker就是目标Invoker
     * 3 为了实现轮询，必须对选中的Invoker的current处理，否则以后每个请求进来选中的就是该Invoker。这里是减去总权重，这样就实现了加权轮循。
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        // 完整方法名
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();

        // 获取 方法名对应的 url唯一标识identifyString 到 WeightedRoundRobin 映射表，如果为空就创建一个新的
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }

        // 总权重
        int totalWeight = 0;

        // 最大权重
        long maxCurrent = Long.MIN_VALUE;

        // 当前时间
        long now = System.currentTimeMillis();

        // 最大当前权重的Invoker 【核心属性】
        Invoker<T> selectedInvoker = null;

        //最大当前权重Invoker对应的权重轮循对象
        WeightedRoundRobin selectedWRR = null;

        /**
         * 循环遍历Invoker集合，主要做以下处理：
         *
         * 1 检查当前Invoker是否有对应的 WeightedRoundRobin ，没有就创建并设置相关属性值
         * 2 检查当前Invoker权重是否发生了变化，如果变化了，则更新WeightedRoundRobin的weight属性
         * 3 更新当前Invoker的当前权重current: current += weight，这一部非常关键
         * 4 更新lastUpdate属性
         * 5 寻找具有最大当前权重的的Invoker以及最大当前权重Invoker对应的WeightedRoundRobin
         * 6 计算权重总和
         */
        for (Invoker<T> invoker : invokers) {
            // url唯一标识identifyString
            String identifyString = invoker.getUrl().toIdentityString();
            // 获得URL串对应的 权重轮循对象
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);

            // 获取当前Invoker的权重
            int weight = getWeight(invoker, invocation);

            // 容错处理
            if (weight < 0) {
                weight = 0;
            }

            // 当前Invoker的URL串没有对应的权重轮循对象，需要创建一个
            if (weightedRoundRobin == null) {
                // 创建权重轮循对象
                weightedRoundRobin = new WeightedRoundRobin();
                // 设置Invoker权重到 权重轮循对象中
                weightedRoundRobin.setWeight(weight);
                // 存储 url唯一标识identifyString 到 weightedRoundRobin 的映射关系
                map.putIfAbsent(identifyString, weightedRoundRobin);

                weightedRoundRobin = map.get(identifyString);
            }

            // 如果当前Invoker权重不等于 缓存中的权重轮循对象 保存的权重 ，说明Invoker的权重改变了，那么就更新权重
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }

            /**
             * 更新并获取当前权重，current += weightedRoundRobin.getWeight()
             * 注意：
             *  1 weightedRoundRobin是个缓存对象，每次有新的请求进来， long cur = weightedRoundRobin.increaseCurrent() 就会随着for循环执行，即 current.addAndGet(weight)
             *  2 每一次请求进来，每个Invoker都会尝试成为当前最大权重的Invoker
             */
            long cur = weightedRoundRobin.increaseCurrent();

            // 设置更新时间，标志最近更新过，这样就不会把对应的Invoker缓存清除掉
            weightedRoundRobin.setLastUpdate(now);

            // 如果Invoker的当前权重大于目前最大权重，则更新最大当前权重，更新最大当前权重Invoker和最大当前权重Invoker关联的 权重轮循对象
            if (cur > maxCurrent) {
                maxCurrent = cur;
                // 将具有最大当前权重的 Invoker 赋值给 selectedInvoker
                selectedInvoker = invoker;
                // 将具有最大当前权重的 Invoker 对应的 weightedRoundRobin 赋值给 selectedWRR，留作后用
                selectedWRR = weightedRoundRobin;
            }

            // 计算权重总和
            totalWeight += weight;
        }

        /**
         * 如果更新标记updateLock 为false，且Invoker集合数不等于缓存数，说明Invoker数量有变化【Invoker集合中不包括缓存中某个Invoker】，
         * 则需要更新 方法名对应的 url唯一标识identifyString 到 WeightedRoundRobin 映射表，因为不在Invoker集合中的Invoker可能已经不可
         * 用了，应及时清除。
         */
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<String, WeightedRoundRobin>();
                    newMap.putAll(map);
                    Iterator<Entry<String, WeightedRoundRobin>> it = newMap.entrySet().iterator();

                    /**
                     * 遍历<identifyString, WeightedRoundRobin> 集合，清除掉长时间未被更新的节点。
                     * 1 该节点可能挂了，Invoker集合中不包含该节点，所以该节点的 lastUpdate 属性长时间没有被更新
                     * 2 清除的条件： 截止到当前，如果未更新时间超过设置的阈值 60 秒就可以清除
                     */
                    while (it.hasNext()) {
                        Entry<String, WeightedRoundRobin> item = it.next();
                        // 未更新时长是否超过阈值 60
                        if (now - item.getValue().getLastUpdate() > RECYCLE_PERIOD) {
                            it.remove();
                        }
                    }

                    // 更新缓存
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }


        // 最大当前权重Invoekr
        if (selectedInvoker != null) {
            // current -= totalWeight   // todo 不要不行吗？？？
            selectedWRR.sel(totalWeight);
            // 返回最大当前权重的Invoker
            return selectedInvoker;
        }

        // should not happen here
        return invokers.get(0);
    }


    /**
     * 仅测试使用
     * <p>
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

}
