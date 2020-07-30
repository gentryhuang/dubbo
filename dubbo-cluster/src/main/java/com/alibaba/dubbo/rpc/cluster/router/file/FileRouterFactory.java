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
package com.alibaba.dubbo.rpc.cluster.router.file;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.IOUtils;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.router.script.ScriptRouterFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * 实现RouterFactory，基于文件读取路由规则
 */
public class FileRouterFactory implements RouterFactory {

    /**
     * 拓展名
     */
    public static final String NAME = "file";

    /**
     * RouterFactory$Adaptive 对象, Dubbo IOC注入
     */
    private RouterFactory routerFactory;

    public void setRouterFactory(RouterFactory routerFactory) {
        this.routerFactory = routerFactory;
    }

    @Override
    public Router getRouter(URL url) {
        try {

            // router 配置项，默认为 script

            // Transform File URL into Script Route URL, and Load
            // file:///d:/path/to/route.js?router=script ==> script:///d:/path/to/route.js?type=js&rule=<file-content>
            String protocol = url.getParameter(Constants.ROUTER_KEY, ScriptRouterFactory.NAME); // Replace original protocol (maybe 'file') with 'script'

            // 使用文件后缀作为类型
            // Use file suffix to config script type, e.g., js, groovy ...
            String type = null;
            String path = url.getPath();


            if (path != null) {
                int i = path.lastIndexOf('.');
                if (i > 0) {
                    type = path.substring(i + 1);
                }
            }

            // 从文件中读取配置规则
            String rule = IOUtils.read(new FileReader(new File(url.getAbsolutePath())));

            // 创建路由规则URL
            boolean runtime = url.getParameter(Constants.RUNTIME_KEY, false);

            // protocol 决定使用哪种路由，默认为script
            URL script = url.setProtocol(protocol)
                    .addParameter(Constants.TYPE_KEY, type)
                    .addParameter(Constants.RUNTIME_KEY, runtime)
                    // 路由规则
                    .addParameterAndEncoded(Constants.RULE_KEY, rule);

            // 获得 Router 对象
            return routerFactory.getRouter(script);

        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

}
