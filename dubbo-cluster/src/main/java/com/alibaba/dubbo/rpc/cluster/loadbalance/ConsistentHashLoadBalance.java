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
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConsistentHashLoadBalance，一致性 Hash，相同参数的请求总是发到同一提供者
 * 说明：
 * 1 当某一台提供者挂时，原本发往该提供者的请求，基于虚拟节点，平摊到其它提供者，不会引起剧烈变动。
 * 2 缺省只对第一个参数hash，并且缺省用160个虚拟节点
 * 3 引入虚拟节点，就是为了均衡各个Invoker的请求量
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    /**
     * 服务方法与一致性哈希选择器的映射
     * key: serviceKey + "." + methodName
     */
    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    /**
     * 选择目标Invoker的前置工作：
     * 1 计算Invoker集合内存地址对应的哈希值
     * 2 检测Invoker集合是不是发生了改变
     * 3 创建哈希选择器
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        // 获得方法名
        String methodName = RpcUtils.getMethodName(invocation);

        // 获得完整方法名： serviceKey + "." + methodName
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;

        // 基于Invoker集合，根据对象内存地址计算哈希值
        int identityHashCode = System.identityHashCode(invokers);

        // 获得 ConsistentHashSelector 对象，若为空，或者计算的哈希值变更 即selector.identityHashCode != identityHashCode（说明服务提供者数量发生了变化，可能新增/减少），则创建新的 ConsistentHashSelector 对象
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        if (selector == null || selector.identityHashCode != identityHashCode) {
            // 创建新的 ConsistentHashSelector，并放入缓存中
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }

        // 使用哈希选择器ConsistentHashSelector选择目标Invoker
        return selector.select(invocation);
    }

    /**
     * 哈希选择器
     *
     * @param <T>
     */
    private static final class ConsistentHashSelector<T> {

        /**
         * 虚拟节点【hash值】与Invoker 的映射关系
         * 说明：
         * 注意这里要求元素有序，因此使用TreeMap
         */
        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        /**
         * 每个Invoker 对应的虚拟节点数
         */
        private final int replicaNumber;

        /**
         * 哈希值
         */
        private final int identityHashCode;

        /**
         * 参与hash计算的参数的下标数组，非参数数组，即选择Invoker的逻辑，需要根据指定的方法参数计算hash值，然后选出目标Invoker。默认为第一个参数，即 0。
         */
        private final int[] argumentIndex;

        /**
         * 构造方法，完成虚拟节点到Invoker的映射
         * <p>
         * 说明：
         * ConsistentHashLoadBalance 的负载均衡逻辑只受参数值影响，具有相同参数值的请求将会被分配给同一个服务提供者。
         *
         * @param invokers         Invoker集合
         * @param methodName       完整方法名
         * @param identityHashCode 哈希值
         */
        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            // 初始化虚拟节点与Invoker 的映射关系
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();

            // 设置hash 值
            this.identityHashCode = identityHashCode;

            // 获得第一个Invoker 的 URL  todo 这些Invoker列表对应的接口是一样的，URL值不同
            URL url = invokers.get(0).getUrl();

            // 获取配置的虚拟节点数，每个Invoker对应的虚拟节点数默认是160，可以修改：<dubbo:parameter key="hash.nodes" value="320" />，使用320个虚拟节点
            this.replicaNumber = url.getMethodParameter(methodName, "hash.nodes", 160);

            // 获取配置的 参与hash计算的参数下标值，默认情况下只对第一个参数Hash,，可以修改： <dubbo:parameter key="hash.arguments" value="0,1" />，hash 第一个和第二个参数
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, "hash.arguments", "0"));

            // 设置参数索引数组
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }

            // 遍历Invoker集合
            for (Invoker<T> invoker : invokers) {

                // 获取Invoker地址： ip:port
                String address = invoker.getUrl().getAddress();

                // 每四个虚拟节点一组
                for (int i = 0; i < replicaNumber / 4; i++) {

                    // md5(address + i串) 运算，得到一个长度为16的字节数组
                    byte[] digest = md5(address + i);

                    // 将16字节的数组每四个字节一组进行hash运算，得到long类型的数，这个long类型的数映射了当前的Invoker。这就是为什么上面把虚拟结点四个划分一组的原因
                    for (int h = 0; h < 4; h++) {

                        // 基于每四个字节，计算虚拟节点hash值
                        long m = hash(digest, h);

                        // 将hash值 到 Invoker 的映射缓存到 TreeMap中【一个Invoker关联多个节点】
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        /**
         * 选择Invoker
         *
         * @param invocation
         * @return
         */
        public Invoker<T> select(Invocation invocation) {
            // 基于指定的方法参数，获得 key，默认根据第一个方法参数获得key
            String key = toKey(invocation.getArguments());
            // 计算 key 的 md5 值
            byte[] digest = md5(key);

            /**
             * 1 取digest数组的前4个字节进行hash运算，得到一个hash值
             * 2 根据hash值到 虚拟节点与Invoker的映射关闭表中 找目标Invoker
             */
            return selectForKey(hash(digest, 0));
        }

        /**
         * 基于指定的方法参数获得key，即拼接指定的参与hash计算的方法参数为字符串，这里参与hash计算的参数取决与配置的参数下标
         *
         * @param args
         * @return
         */
        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            // 遍历配置的参数hash计算的参数下标数组
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    // 拼接下标对应的参数
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        /**
         * 从虚拟节点与Invoker 的映射关系中取出对应的Invoker
         *
         * @param hash
         * @return
         */
        private Invoker<T> selectForKey(long hash) {
            // 从虚拟节点与Invoker的映射关系的TreeMap中查找第一个虚拟节点值大于或等于当前hash的元素
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.tailMap(hash, true).firstEntry();

            // 如果hash大于映射关系表中最大的虚拟节点值，那么就取映射关系表中的头节点
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }

            // 返回目标Invoker
            return entry.getValue();
        }

        /**
         * digest每四个字节，组成一个long类型的值
         *
         * @param digest
         * @param number
         * @return
         */
        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        /**
         * md5加密
         *
         * @param value
         * @return
         */
        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes;
            try {
                bytes = value.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.update(bytes);
            return md5.digest();
        }

    }

}
