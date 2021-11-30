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
import org.apache.dubbo.rpc.support.RpcUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * ConsistentHashLoadBalance
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    /**
     * 扩展名
     */
    public static final String NAME = "consistenthash";

    /**
     * 虚拟节点数配置项目，默认值为 160
     * 格式：<dubbo:parameter key="hash.nodes" value="320" />
     */
    public static final String HASH_NODES = "hash.nodes";

    /**
     * 参与Hash计算的参数索引，默认只对第一个参数Hash
     * 格式：<dubbo:parameter key="hash.arguments" value="0,1" />
     */
    public static final String HASH_ARGUMENTS = "hash.arguments";

    /**
     * key: ServiceKey.methodName -> 完整方法名
     * value: ConsistentHashSelector
     */
    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    /**
     * 相同参数的请求总是发到同一提供者
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
        // 1 获取调用的方法名称
        String methodName = RpcUtils.getMethodName(invocation);
        // 2 将 ServiceKey 和 方法名 拼接起来构成一个 key，即完整方法名
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;

        // 3 获取 Invoker 列表的 hashcode（为了在 Invokers 列表发生变化时重新生成 ConsistentHashSelector 对象）
        int invokersHashCode = invokers.hashCode();
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);

        // 4 如果 invokers 是一个新的 List 对象，说明服务提供者数量发生了变化，可能新增也可能减少了
        // 此时 selector.identityHashCode != invokersHashCode 成立
        if (selector == null || selector.identityHashCode != invokersHashCode) {
            // 创建 ConsistentHashSelector 对象
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, invokersHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }

        // 5 通过 ConsistentHashSelector 对象选择一个 Invoker 对象
        return selector.select(invocation);
    }

    private static final class ConsistentHashSelector<T> {

        /**
         * 使用 TreeMap 存储 Invoker 虚拟节点，TreeMap 是按照Key排序的
         */
        private final TreeMap<Long, Invoker<T>> virtualInvokers;
        /**
         * Invoker 虚拟节点个数
         */
        private final int replicaNumber;
        /**
         * Invoker 集合的 HashCode 值
         */
        private final int identityHashCode;
        /**
         * 需要参与 Hash 计算的参数索引。
         * 如：argumentIndex = [0,1,2] 时，表示调用的目标方法的前三个参数要参与 Hash 计算。
         */
        private final int[] argumentIndex;

        /**
         * 1 构建 Hash 槽
         * 2 确认参与一致性 Hash 计算的参数，默认是第一个参数
         *
         * @param invokers         消费端 Invoker 列表
         * @param methodName       方法名
         * @param identityHashCode Invoker 列表的 hashCode 的值
         */
        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            // 1 初始化 virtualInvokers 字段，用于缓存 Invoker 的虚拟节点
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();

            // 2 记录 Invoker 集合的 hashCode，用该 hashCode 值可以判断 Provider 列表是否发生了变化
            this.identityHashCode = identityHashCode;

            // 3 获取消费端 Invoker 的 URL
            URL url = invokers.get(0).getUrl();

            // 4 从配置中获取虚拟节点数（hash.nodes 参数）以及参与 hash 计算的参数下标（hash.arguments 参数）
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);

            // 5 对参与 hash  计算的参数下标进行解析，然后存放到 argumentIndex 数组中
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }

            // 6 构建 Invoker 虚拟节点，默认 replicaNumber=160，相当于在 Hash 环上放 160 个槽位。外层轮询 40 次，内层轮询 4 次，共 40 * 4 = 160次，也就是同一个节点虚拟出 160 个槽位
            for (Invoker<T> invoker : invokers) {
                // 6.1 获取服务地址 host:port
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 6.2 对 address + i 进行md5运算，得到一个长度为16的字节数组
                    // 基于服务地址进行 md5 计算
                    byte[] digest = md5(address + i);

                    // 6.3 对 digest 部分字节进行 4 次 Hash 运算，得到 4 个不同的 long 型正整数
                    for (int h = 0; h < 4; h++) {
                        // h = 0 时，取 digest 中下标为 0 ~ 3 的4个字节进行位运算
                        // h = 1 时，取 digest 中下标为 4 ~ 7 的4个字节进行位运算
                        // h = 2, h = 3 时过程同上
                        long m = hash(digest, h);

                        // 6.3 将 hash 到 Invoker 的映射关系存储到 virtualInvokers 中
                        // virtualInvokers 需要提供高效、有序的查询擦操作，因此选用 TreeMap 作为存储结构
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        /**
         * 选择合适的 Invoker 对象
         *
         * @param invocation
         * @return
         */
        public Invoker<T> select(Invocation invocation) {
            // 1 将参与一致性 Hash 的参数拼接到一起
            String key = toKey(invocation.getArguments());
            // 2 对 key 进行 md5 运算
            byte[] digest = md5(key);
            // 3 取 digest 数组的前四个字节进行 hash 运算，再将 hash 值传给 selectForKey 方法，寻找合适的 Invoker
            return selectForKey(hash(digest, 0));
        }

        /**
         * 将参与 Hash 计算的参数索引对应的参数值进行拼接。默认对第一个参数进行 Hash 运算。
         *
         * @param args
         * @return
         */
        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            // 对参与 Hash 计算的参数值进行拼接
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        /**
         * 选择 Invoker
         *
         * @param hash 调用方法参数处理后的 Hash 值
         * @return
         */
        private Invoker<T> selectForKey(long hash) {
            // 1 到 TreeMap 中查找第一个节点值大于或等于当前 hash 的 Invoker
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);

            // 2 如果传入的 hash 大于 Invoker 在 Hash 环上最大的位置，此时 entry = null，此时需要回到 Hash 环的开头返回第一个 Invoker 对象
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }

            // 3 取出目标 Invoker
            return entry.getValue();
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }

}
