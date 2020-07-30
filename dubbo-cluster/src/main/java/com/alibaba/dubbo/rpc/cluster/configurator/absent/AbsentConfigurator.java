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
package com.alibaba.dubbo.rpc.cluster.configurator.absent;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.cluster.configurator.AbstractConfigurator;

/**
 * AbsentConfigurator，不存在时添加，即当url的parameters部分中不存在配置Url的parameters部分的键值对参数，才会添加，存在则使用url自己的
 * 说明：
 * 目前dubbo-admin项目暂时未使用 absent的配置规则
 */
public class AbsentConfigurator extends AbstractConfigurator {

    /**
     * 构造方法会调用父类的构造方法
     *
     * @param url
     */
    public AbsentConfigurator(URL url) {
        super(url);
    }

    @Override
    public URL doConfigure(URL currentUrl, URL configUrl) {
        return currentUrl.addParametersIfAbsent(configUrl.getParameters());
    }

}
