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

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;

/**
 * 实现Cluster接口，分组聚合Cluster实现类。用于创建MergeableClusterInvoker对象，它同样遵循Cluster的设计模式，在MergeableCluster生成的Invoker方法
 * 中完成具体逻辑，该逻辑中会使用Merger接口的具体实现来合并结果集，具体使用哪个实现，是通过MergerFactory获得各种具体的Merger实现。
 * 注意：
 * 它的使用方式不同其它的CLuster，配置如： <dubbo:reference interface="com.xxx.MenuService" group="aaa,bbb" merger="true" />
 */
public class MergeableCluster implements Cluster {
    /**
     * 扩展实现名
     */
    public static final String NAME = "mergeable";

    /**
     * 创建MergeableClusterInvoker
     *
     * @param directory Directory 对象
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        return new MergeableClusterInvoker<T>(directory);
    }

}
