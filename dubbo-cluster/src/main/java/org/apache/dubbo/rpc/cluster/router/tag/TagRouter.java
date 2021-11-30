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
package org.apache.dubbo.rpc.cluster.router.tag;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.ConfigChangedEvent;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRouterRule;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRuleParser;

import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;

/**
 * TagRouter, "application.tag-router"
 * 说明：
 * 1 通过 TagRouter 可以将某一个或多个 Provider 划分到同一组，约束流量只在指定分组中流转，这样就可以轻松达到流量隔离的目的，从而支持灰度发布等场景。
 * 2 标签主要是指对 Provider 端应用实例的分组，目前有两种方式可以完成实例分组，分别是 动态规则打标 和 静态规则打标
 */
public class TagRouter extends AbstractRouter implements ConfigurationListener {
    private static final Logger logger = LoggerFactory.getLogger(TagRouter.class);

    public static final String NAME = "TAG_ROUTER";
    /**
     * 优先级
     */
    private static final int TAG_ROUTER_DEFAULT_PRIORITY = 100;

    private static final String RULE_SUFFIX = ".tag-router";
    private String application;

    /**
     * 标签路由规则
     */
    private TagRouterRule tagRouterRule;


    public TagRouter(URL url) {
        super(url);
        // 设置优先级
        this.priority = TAG_ROUTER_DEFAULT_PRIORITY;
    }

    /**
     * 监听配置的变化
     *
     * @param event config change event
     */
    @Override
    public synchronized void process(ConfigChangedEvent event) {
        if (logger.isDebugEnabled()) {
            logger.debug("Notification of tag rule, change type is: " + event.getChangeType() + ", raw rule is:\n " +
                    event.getContent());
        }

        try {
            // 1 DELETED 事件会直接清空 tagRouterRule
            if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
                this.tagRouterRule = null;

                // 2  其它事件会解析最新的路由规则，并记录到 tagRouterRule 字段中
            } else {
                // 通过 TagRuleParser 解析配置
                this.tagRouterRule = TagRuleParser.parse(event.getContent());
            }
        } catch (Exception e) {
            logger.error("Failed to parse the raw tag router rule and it will not take effect, please check if the " +
                    "rule matches with the template, the raw rule is:\n ", e);
        }
    }

    @Override
    public URL getUrl() {
        return url;
    }

    /***
     * 服务路由
     * @param invokers   invoker list
     * @param url        refer url
     * @param invocation invocation
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        // 1 如果 invokers 为空，直接返回空集合
        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }

        // 2 优先使用动态规则
        // 检查动态规则 tagRouterRule 是否可用，如果不可用，则调用 filterUsingStaticTag 方法使用静态规则进行过滤
        final TagRouterRule tagRouterRuleCopy = tagRouterRule;
        if (tagRouterRuleCopy == null || !tagRouterRuleCopy.isValid() || !tagRouterRuleCopy.isEnabled()) {
            return filterUsingStaticTag(invokers, url, invocation);
        }

        // 使用动态规则
        List<Invoker<T>> result = invokers;

        // 3 获取此次请求的 tag 信息，尝试从 Invocation 以及 URL 中获取
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
                invocation.getAttachment(TAG_KEY);

        // 4 此次请求指定了 tag (优先选择tag对应的Provider，如果没有Provider则降级处理，选择 tag 为空的 Provider)
        if (StringUtils.isNotEmpty(tag)) {
            // 4.1 获取 tag 关联的 address 集合
            List<String> addresses = tagRouterRuleCopy.getTagnameToAddresses().get(tag);
            if (CollectionUtils.isNotEmpty(addresses)) {
                // 4.1.2 根据请求 tag 对应的 address 集合去匹配符合条件的 Invoker
                result = filterInvoker(invokers, invoker -> addressMatches(invoker.getUrl(), addresses));

                // 如果存在符合条件的Invoker，则直接将过滤得到的Invoker集合返回。否则，根据force配置决定是否返回空Invoker集合
                if (CollectionUtils.isNotEmpty(result) || tagRouterRuleCopy.isForce()) {
                    return result;
                }

                // 4.2 如果请求 tag 没有对应 address
            } else {

                // 4.2.1 将请求携带的 tag 与 Provider URL 中的 tag 参数值进行比较，匹配出符合条件的 Invoker 集合。
                result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            }

            // 4.3 存在符合条件的Invoker 或是 force=true，则直接返回过滤后的结果
            if (CollectionUtils.isNotEmpty(result) || isForceUseTag(invocation)) {
                return result;
            }

            // 4.4 如果 Invoker 过滤后的结果为空，且 force 配置为 false，则返回所有不包含任何 tag 的 Provider 列表。(降级)
            // FAILOVER: return all Providers without any tags.
            else {
                List<Invoker<T>> tmp = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(), tagRouterRuleCopy.getAddresses()));
                return filterInvoker(tmp, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }

            // 5 如果此次请求未携带 tag 信息（只匹配tag为空的Provier）
        } else {
            // List<String> addresses = tagRouterRule.filter(providerApp);
            // return all addresses in dynamic tag group.

            // 5.1 获取 TagRouterRule 规则中全部 tag 关联的 address 集合
            List<String> addresses = tagRouterRuleCopy.getAddresses();

            // 5.2 如果 address 集合不为空，则过滤出不在 address 集合中的 Invoker 并添加到结果集合中。
            if (CollectionUtils.isNotEmpty(addresses)) {
                result = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(), addresses));
                // 1. all addresses are in dynamic tag group, return empty list.
                if (CollectionUtils.isEmpty(result)) {
                    return result;
                }
                // 2. if there are some addresses that are not in any dynamic tag group, continue to filter using the
                // static tag group.
            }

            // 5.3 将 Provider URL 中的 tag 值与 TagRouterRule 中的 tag 名称进行比较，得到最终的 Invoker 集合。
            return filterInvoker(result, invoker -> {
                String localTag = invoker.getUrl().getParameter(TAG_KEY);
                return StringUtils.isEmpty(localTag) || !tagRouterRuleCopy.getTagNames().contains(localTag);
            });
        }
    }

    /**
     * If there's no dynamic tag rule being set, use static tag in URL.
     * <p>
     * A typical scenario is a Consumer using version 2.7.x calls Providers using version 2.6.x or lower,
     * the Consumer should always respect the tag in provider URL regardless of whether a dynamic tag rule has been set to it or not.
     * <p>
     * TODO, to guarantee consistent behavior of interoperability between 2.6- and 2.7+, this method should has the same logic with the TagRouter in 2.6.x.
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> filterUsingStaticTag(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        List<Invoker<T>> result = invokers;

        // 1 获取请求携带的 tag
        // Dynamic param
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
                invocation.getAttachment(TAG_KEY);


        // 2 请求携带了 tag
        // Tag request
        if (!StringUtils.isEmpty(tag)) {
            // 2.1 比较请求携带的 tag 值与 Provider URL 中的 tag 参数值
            result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));

            // 2.2 集群中不存在与请求tag对应的服务，默认降级请求 tag 为空的 Provider。
            if (CollectionUtils.isEmpty(result) && !isForceUseTag(invocation)) {
                result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }

            // 3 请求没有携带 tag，只会匹配 tag 为空的 Provider 。
        } else {
            result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
        }
        return result;
    }

    @Override
    public boolean isRuntime() {
        return tagRouterRule != null && tagRouterRule.isRuntime();
    }

    @Override
    public boolean isForce() {
        // FIXME
        return tagRouterRule != null && tagRouterRule.isForce();
    }

    private boolean isForceUseTag(Invocation invocation) {
        return Boolean.valueOf(invocation.getAttachment(FORCE_USE_TAG, url.getParameter(FORCE_USE_TAG, "false")));
    }

    private <T> List<Invoker<T>> filterInvoker(List<Invoker<T>> invokers, Predicate<Invoker<T>> predicate) {
        return invokers.stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    private boolean addressMatches(URL url, List<String> addresses) {
        return addresses != null && checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean addressNotMatches(URL url, List<String> addresses) {
        return addresses == null || !checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean checkAddressMatch(List<String> addresses, String host, int port) {
        for (String address : addresses) {
            try {
                if (NetUtils.matchIpExpression(address, host, port)) {
                    return true;
                }
                if ((ANYHOST_VALUE + ":" + port).equals(address)) {
                    return true;
                }
            } catch (UnknownHostException e) {
                logger.error("The format of ip address is invalid in tag route. Address :" + address, e);
            } catch (Exception e) {
                logger.error("The format of ip address is invalid in tag route. Address :" + address, e);
            }
        }
        return false;
    }

    public void setApplication(String app) {
        this.application = app;
    }

    @Override
    public <T> void notify(List<Invoker<T>> invokers) {
        if (CollectionUtils.isEmpty(invokers)) {
            return;
        }

        Invoker<T> invoker = invokers.get(0);
        URL url = invoker.getUrl();
        String providerApplication = url.getParameter(CommonConstants.REMOTE_APPLICATION_KEY);

        if (StringUtils.isEmpty(providerApplication)) {
            logger.error("TagRouter must getConfig from or subscribe to a specific application, but the application " +
                    "in this TagRouter is not specified.");
            return;
        }

        synchronized (this) {
            if (!providerApplication.equals(application)) {
                if (!StringUtils.isEmpty(application)) {
                    ruleRepository.removeListener(application + RULE_SUFFIX, this);
                }
                String key = providerApplication + RULE_SUFFIX;
                ruleRepository.addListener(key, this);
                application = providerApplication;
                String rawRule = ruleRepository.getRule(key, DynamicConfiguration.DEFAULT_GROUP);
                if (StringUtils.isNotEmpty(rawRule)) {
                    this.process(new ConfigChangedEvent(key, DynamicConfiguration.DEFAULT_GROUP, rawRule));
                }
            }
        }
    }

}
