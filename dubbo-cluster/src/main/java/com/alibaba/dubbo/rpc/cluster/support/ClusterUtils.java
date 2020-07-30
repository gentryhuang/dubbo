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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;

import java.util.HashMap;
import java.util.Map;

/**
 * ClusterUtils
 */
public class ClusterUtils {

    private ClusterUtils() {
    }

    /**
     * 将localMap 和 remoteUrl.parameters 合并到 map
     *
     * @param remoteUrl
     * @param localMap
     * @return
     */
    public static URL mergeUrl(URL remoteUrl, Map<String, String> localMap) {
        // 合并配置的结果
        Map<String, String> map = new HashMap<String, String>();

        // 远程配置的参数集合【参数部分，不是整个配置项】
        Map<String, String> remoteMap = remoteUrl.getParameters();

        // 将 远程配置的参数数据 加入到结果中，并且结果集会异常不必要的参数配置
        if (remoteMap != null && remoteMap.size() > 0) {

            map.putAll(remoteMap);

            // Remove configurations from provider, some items should be affected by provider.
            // 1 移除 threadname
            map.remove(Constants.THREAD_NAME_KEY);
            // 2 移除 default.threadname
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.THREAD_NAME_KEY);
            // 3 移除 threadpool
            map.remove(Constants.THREADPOOL_KEY);
            // 4 移除 default.threadpool
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.THREADPOOL_KEY);
            // 5 移除 corethreads
            map.remove(Constants.CORE_THREADS_KEY);
            // 6 移除 default.corethreads
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.CORE_THREADS_KEY);
            // 7 移除 threads
            map.remove(Constants.THREADS_KEY);
            // 8 移除 default.threads
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.THREADS_KEY);
            // 9 移除 queues
            map.remove(Constants.QUEUES_KEY);
            // 10 移除 default.queues
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.QUEUES_KEY);
            // 11 移除 alive
            map.remove(Constants.ALIVE_KEY);
            // 12 移除 default.alive
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.ALIVE_KEY);
            // 13 移除 transporter
            map.remove(Constants.TRANSPORTER_KEY);
            // 14 移除 default.transporter
            map.remove(Constants.DEFAULT_KEY_PREFIX + Constants.TRANSPORTER_KEY);
        }

        // 添加 localMap 配置项 到 map 中
        if (localMap != null && localMap.size() > 0) {
            map.putAll(localMap);
        }

        // 添加指定 远程配置的参数 到map 中，因为上面把localMap直接加入到了map中，导致了map中远程配置的部分参数被覆盖，需要重新显示添加
        if (remoteMap != null && remoteMap.size() > 0) {
            // dubbo 参数  使用 provider side
            String dubbo = remoteMap.get(Constants.DUBBO_VERSION_KEY);
            if (dubbo != null && dubbo.length() > 0) {
                map.put(Constants.DUBBO_VERSION_KEY, dubbo);
            }
            // version 参数 使用 provider side
            String version = remoteMap.get(Constants.VERSION_KEY);
            if (version != null && version.length() > 0) {
                map.put(Constants.VERSION_KEY, version);
            }
            // group 参数 使用 provider side
            String group = remoteMap.get(Constants.GROUP_KEY);
            if (group != null && group.length() > 0) {
                map.put(Constants.GROUP_KEY, group);
            }
            // methods 参数 使用 provider side
            String methods = remoteMap.get(Constants.METHODS_KEY);
            if (methods != null && methods.length() > 0) {
                map.put(Constants.METHODS_KEY, methods);
            }
            // remote.timestamp 参数 使用 provider side
            String remoteTimestamp = remoteMap.get(Constants.TIMESTAMP_KEY);
            if (remoteTimestamp != null && remoteTimestamp.length() > 0) {
                map.put(Constants.REMOTE_TIMESTAMP_KEY, remoteMap.get(Constants.TIMESTAMP_KEY));
            }

            /**
             * Combine filters and listeners on Provider and Consumer  合并服务提供者和消费者的filter和listener
             */
            String remoteFilter = remoteMap.get(Constants.REFERENCE_FILTER_KEY);
            String localFilter = localMap.get(Constants.REFERENCE_FILTER_KEY);
            if (remoteFilter != null && remoteFilter.length() > 0 && localFilter != null && localFilter.length() > 0) {
                localMap.put(Constants.REFERENCE_FILTER_KEY, remoteFilter + "," + localFilter);
            }
            String remoteListener = remoteMap.get(Constants.INVOKER_LISTENER_KEY);
            String localListener = localMap.get(Constants.INVOKER_LISTENER_KEY);
            if (remoteListener != null && remoteListener.length() > 0 && localListener != null && localListener.length() > 0) {
                localMap.put(Constants.INVOKER_LISTENER_KEY, remoteListener + "," + localListener);
            }
        }

        // 将合并得到的map，完全覆盖到 remoteUrl 中
        return remoteUrl.clearParameters().addParameters(map);
    }

}