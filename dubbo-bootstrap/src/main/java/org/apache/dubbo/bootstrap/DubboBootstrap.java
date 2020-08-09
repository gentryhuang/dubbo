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
package org.apache.dubbo.bootstrap;

import com.alibaba.dubbo.config.DubboShutdownHook;
import com.alibaba.dubbo.config.ServiceConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * A bootstrap class to easily start and stop Dubbo via programmatic API.
 * The bootstrap class will be responsible to cleanup the resources during stop.
 */
public class DubboBootstrap {

    /**
     * 服务配置对象列表
     */
    private List<ServiceConfig> serviceConfigList;

    /**
     * 启动期间是否注册 钩子
     */
    private final boolean registerShutdownHookOnStart;

    /**
     * 在嵌入式环境下[Main方法]运行Dubbo时使用的 钩子
     */
    private DubboShutdownHook shutdownHook;

    public DubboBootstrap() {
        this(true, DubboShutdownHook.getDubboShutdownHook());
    }

    public DubboBootstrap(boolean registerShutdownHookOnStart) {
        this(registerShutdownHookOnStart, DubboShutdownHook.getDubboShutdownHook());
    }

    public DubboBootstrap(boolean registerShutdownHookOnStart, DubboShutdownHook shutdownHook) {
        this.serviceConfigList = new ArrayList<ServiceConfig>();
        this.shutdownHook = shutdownHook;
        this.registerShutdownHookOnStart = registerShutdownHookOnStart;
    }

    /**
     * Register service config to bootstrap, which will be called during {@link DubboBootstrap#stop()}
     * @param serviceConfig the service
     * @return the bootstrap instance
     */
    public DubboBootstrap registerServiceConfig(ServiceConfig serviceConfig) {
        serviceConfigList.add(serviceConfig);
        return this;
    }

    /**
     * dubbo引导程序 - start
     * 1 是否注册shutdown hook
     * 2 服务暴露
     */
    public void start() {
        // 启动期间是否注册过shutdown hook
        if (registerShutdownHookOnStart) {
            registerShutdownHook();
        } else {
            // 如果DubboShutdown hook 已经注册到系统中，需要移除掉
            removeShutdownHook();
        }
        // 循环服务配置对象，依次进行服务暴露
        for (ServiceConfig serviceConfig: serviceConfigList) {
            serviceConfig.export();
        }
    }

    /**
     * dubbo引导程序 - stop
     * 1 取消服务暴露
     * 2
     */
    public void stop() {
        for (ServiceConfig serviceConfig: serviceConfigList) {
            serviceConfig.unexport();
        }
        // 执行 shutdown hook 释放资源
        shutdownHook.destroyAll();

        // 如果启动期已经注册过，则从系统中移除 todo ??? 为什么还要注册到系统，直接根据spring销毁事件然后执行释放任务不就可以了吗？
        if (registerShutdownHookOnStart) {
            removeShutdownHook();
        }
    }

    /**
     * 注册 shutdown hook
     */
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * 移除 shutdown hook
     */
    public void removeShutdownHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        catch (IllegalStateException ex) {
            // ignore - VM is already shutting down
        }
    }
}
