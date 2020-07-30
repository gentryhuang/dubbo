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
package com.alibaba.dubbo.rpc.cluster.configurator.override;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.cluster.configurator.AbstractConfigurator;

/**
 * AbsentConfigurator，直接覆盖添加，即 配置Url的parameters部分 直接 覆盖 url的parameters部分，如果出现相同key，则以配置Url为主
 */
public class OverrideConfigurator extends AbstractConfigurator {

    /**
     * 构造方法会调用父类的构造方法
     * @param url
     */
    public OverrideConfigurator(URL url) {
        super(url);
    }

    @Override
    public URL doConfigure(URL currentUrl, URL configUrl) {
        // 覆盖添加
        return currentUrl.addParameters(configUrl.getParameters());
    }

}
