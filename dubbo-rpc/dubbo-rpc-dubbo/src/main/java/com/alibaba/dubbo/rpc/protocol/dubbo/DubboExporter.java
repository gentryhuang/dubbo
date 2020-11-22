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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * DubboExporter
 */
public class DubboExporter<T> extends AbstractExporter<T> {

    /**
     * 服务键
     */
    private final String key;
    /**
     * Exporter 集合
     * key : 服务键
     */
    private final Map<String, Exporter<?>> exporterMap;

    /**
     * 构造方法发起暴露
     *
     * @param invoker     Invoker
     * @param key         服务键
     * @param exporterMap AbstractProtocol.exporterMap属性
     */
    public DubboExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    /**
     * 取消暴露
     */
    @Override
    public void unexport() {
        // 取消暴露
        super.unexport();
        // 移除自己，即清理该 DubboExporter 实例在 exporterMap 中相应的元素
        exporterMap.remove(key);
    }

}