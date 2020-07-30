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
package com.alibaba.dubbo.rpc.cluster.configurator;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AbstractOverrideConfigurator，实现公用的配置规则的匹配，排序的逻辑
 */
public abstract class AbstractConfigurator implements Configurator {

    /**
     * 配置规则url
     * 说明：
     *  该url必须要的结构：http://dubbo.apache.org/zh-cn/docs/user/demos/config-rule-deprecated.html
     */
    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    /**
     * 获得配置规则URL
     *
     * @return
     */
    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    /**
     * 设置配置规则到指定URl中
     *
     * @param url 待应用配置规则的URL
     * @return
     */
    @Override
    public URL configure(URL url) {

        // 参数检查，不允许配置规则Url为null且host不能为null，待处理的Url不能为null且host不能为null
        if (configuratorUrl == null || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }

        //  配置规则Url有端口，则说明这个配置规则Url是操作某个服务提供者的，可以通过配置Url的特性参数来控制服务提供者。配置成功后，既可以在服务提供者端生效，也可以在服务消费端生效
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        if (configuratorUrl.getPort() != 0) {
            // 配置规则Url有端口，且和待处理的url的端口一致
            if (url.getPort() == configuratorUrl.getPort()) {
                // 因为操作的是服务提供者，所以这里使用的是url的host
                return configureIfMatch(url.getHost(), url);
            }

            // 配置规则Url没有端口，则说明这个配置url是操作消费者的，或单纯是0.0.0.0
            // override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0
        } else {

            // 1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore;
            // 2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider

            //  如果url是消费端地址，则可以控制消费者，只在消费端生效，服务提供者收到后忽略
            if (url.getParameter(Constants.SIDE_KEY, Constants.PROVIDER).equals(Constants.CONSUMER)) {
                // NetUtils.getLocalHost is the ip address consumer registered to registry ，
                // 因为操作的是消费者，所以这里使用的是NetUtils.getLocalHost()， 是消费端注册到注册中心的消费者地址。 todo 为啥子不是url.getHost ？？？
                return configureIfMatch(NetUtils.getLocalHost(), url);

                // 如果url是服务端地址，意图匹配全部服务提供者。【注意：暂时不支持指定机器服务提供者】
            } else if (url.getParameter(Constants.SIDE_KEY, Constants.CONSUMER).equals(Constants.PROVIDER)) {
                // 对所有服务端生效，因此地址必须是0.0.0.0，否则它将不会流到此if分支
                // take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
                return configureIfMatch(Constants.ANYHOST_VALUE, url);
            }
        }
        return url;
    }

    /**
     * 给传入的URL使用配置规则，主要做了两个任务：
     * <p>
     * 1 判断要使用配置规则的URL是否符合规则
     * 2 配置规则Url去除parameters中条件参数后，应用到目标Url上，对目标Url的parameters是直接全覆盖，还是选择性覆盖，根据具体的配置规则对象。
     *
     * @param host
     * @param url
     * @return
     */
    private URL configureIfMatch(String host, URL url) {

        //1 匹配host , 如果配置url的host为0.0.0.0，或者 配置url的host等于传入的host，则继续匹配应用。否则直接返回url
        if (Constants.ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {

            // 获得配置url中的application，即应用名 【不指定就表示对所有应用生效】
            String configApplication = configuratorUrl.getParameter(Constants.APPLICATION_KEY, configuratorUrl.getUsername());
            // 获得url的application，即应用名
            String currentApplication = url.getParameter(Constants.APPLICATION_KEY, url.getUsername());

            //2 匹配应用， 如果配置url的应用名为null，或者为 "*"，或者和url的应用名相同
            if (configApplication == null || Constants.ANY_VALUE.equals(configApplication) || configApplication.equals(currentApplication)) {

                // 配置Url中的条件集合，这四个是内置的。除了内置的，还可以包括  带有"～"开头的key、"application" 、 "side"
                Set<String> conditionKeys = new HashSet<String>();

                // category
                conditionKeys.add(Constants.CATEGORY_KEY);
                // check
                conditionKeys.add(Constants.CHECK_KEY);
                // dynamic
                conditionKeys.add(Constants.DYNAMIC_KEY);
                // enabled
                conditionKeys.add(Constants.ENABLED_KEY);

                /**
                 * 遍历配置url的 parameter 参数集合
                 * 1 把符合要求的条件加入到配置Url的条件集合，即：带有"～"开头的key、"application" 、 "side"
                 * 2 判断传入的url是否匹配配置规则Url的条件，注意是parameter部分比较，并且不是整个parameter集合的比较，只是  "～"开头的key 或 "application" 或 "side" 这个三个key/valu的比较
                 */
                for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                    // 参数key
                    String key = entry.getKey();
                    // 参数key对应的value
                    String value = entry.getValue();

                    // 如果 配置url的parameter参数的key是： "～" 或 "application" 或 "side"，那么也加入配置Url的条件集合中
                    if (key.startsWith("~") || Constants.APPLICATION_KEY.equals(key) || Constants.SIDE_KEY.equals(key)) {
                        // 把key加入到条件集合中
                        conditionKeys.add(key);

                        // 如果不相等，则url不匹配配置规则，直接返回url
                        if (value != null && !Constants.ANY_VALUE.equals(value) && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                            return url;
                        }
                    }

                }

                // 从配置Url中移除配置Url的条件集合，并配置到URL中
                return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
            }
        }

        return url;
    }

    /**
     * 比较，先按照host升序，其次按照priority降序
     * <p>
     * Sort by host, priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        // 根据host升序
        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());

        // 如果host相同，则按照priority降序
        if (ipCompare == 0) {
            int i = getUrl().getParameter(Constants.PRIORITY_KEY, 0),
                    j = o.getUrl().getParameter(Constants.PRIORITY_KEY, 0);
            return i < j ? -1 : (i == j ? 0 : 1);
        } else {
            return ipCompare;
        }


    }

    /**
     * 将配置Url配置到url中
     *
     * @param currentUrl url
     * @param configUrl  配置url
     * @return
     */
    protected abstract URL doConfigure(URL currentUrl, URL configUrl);


    public static void main(String[] args) {
        System.out.println(URL.encode("timeout=100"));
    }

}
