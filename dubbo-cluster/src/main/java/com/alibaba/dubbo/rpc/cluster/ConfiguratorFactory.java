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
package com.alibaba.dubbo.rpc.cluster;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * ConfiguratorFactory. (SPI, Singleton, ThreadSafe)
 * <p>
 * 1  Configurator工厂接口，Dubbo SPI扩展点，没有默认值。
 * 2  @Adaptive("protocol")注解，基于Dubbo 自适应机制加载对应的ConfiguratorFactory实现，即根据配置规则Url的protocol属性，获取Configurator实现，
 * 目前配置URL中的协议支持 override和absent，对应的实现分别为：OverrideConfiguratorFactory 和 AbsentConfiguratorFactory
 */
@SPI
public interface ConfiguratorFactory {

    /**
     * 创建Configurator 配置规则对象
     * <p>
     * get the configurator instance.
     *
     * @param url - configurator url.
     * @return configurator instance.
     */
    @Adaptive("protocol")
    Configurator getConfigurator(URL url);

}
