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
package com.alibaba.dubbo.rpc.cluster.router.condition;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter，基于条件表达式的Router实现类,路由规则在发起一次RPC调用前起到过滤目标服务器地址的作用，过滤后的地址列表，将作为消费端最终发起RPC调用的备选地址。
 * 流程说明：
 * 1 对用户配置的路由规则进行解析，得到匹配项集合,即其对应的匹配值集合和不匹配值集合，
 * 2 当需要进行匹配的时候，根据已经解析好的规则对 消费者URL或服务提供者URL 进行匹配，以达到过滤Invoker的目的，即消费者快要符合哪些规则，提供者要符合哪些规则
 */
public class ConditionRouter implements Router, Comparable<Router> {

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);

    /**
     * 分组正则匹配
     * 说明：
     * 第一个匹配组： 用于匹配 "&", "!", "=" 和 "," 等符号，作为匹配规则的分隔符。允许匹配不到，使用了 * 通配符
     * 第二个匹配组： 这里用于匹配 英文字母，数字等字符，【其实是匹配不是 &!=,】作为匹配规则的匹配内容。 可能出现，这里匹配到了，但是第一匹配组没有匹配到。
     */
    private static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    /**
     * 路由规则 URL，如： URL.valueOf("route://0.0.0.0/com.foo.BarService?category=routers&dynamic=false&rule=" + URL.encode("host = 10.20.153.10 => host = 10.20.153.11"))
     */
    private final URL url;
    /**
     * 路由规则优先级，用于排序，优先级越大越靠前。优先级越大越靠前执行。默认为0
     */
    private final int priority;
    /**
     * 当路由结果为空时是否强制执行，如果不强制执行，路由匹配结果为空的路由规则将自动失效。默认为false
     */
    private final boolean force;
    /**
     * 消费者匹配条件集合，通过解析条件表达式规则 '=>' 之前的部分
     * key: 匹配项
     * value: 匹配项对应的匹配对 【包含匹配项对应的 匹配值集合/不匹配值集合 】
     * 效果：所有参数和消费者的 URL 进行对比，当消费者满足匹配条件时，对该消费者执行后面的过滤规则。
     */
    private final Map<String, MatchPair> whenCondition;
    /**
     * 提供者地址列表的过滤条件，通过解析条件表达式规则 '=>' 之后的部分
     * key: 匹配项
     * value: 匹配项对应的匹配对 【包含匹配项对应的 匹配值集合/不匹配值集合 】
     * 效果：所有参数和提供者的 URL 进行对比，消费者最终只拿到过滤后的地址列表。
     */
    private final Map<String, MatchPair> thenCondition;

    /**
     * 将条件路由规则解析成预定格式
     *
     * @param url
     */
    public ConditionRouter(URL url) {
        this.url = url;

        // 获取 priority 和 force 配置
        this.priority = url.getParameter(Constants.PRIORITY_KEY, 0);
        this.force = url.getParameter(Constants.FORCE_KEY, false);

        try {

            // 获取路由规则URL中 路由规则rule参数的值
            String rule = url.getParameterAndDecoded(Constants.RULE_KEY);
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }

            // 剔除掉路由规则中的consumer.或者provider. ，如 consumer.host != 192.168.0.1 & method = * => provider.host != 10.75.25.66 ，剔除调前缀才是真正的规则
            rule = rule.replace("consumer.", "").replace("provider.", "");

            // 根据 "=>" 拆分路由规则
            int i = rule.indexOf("=>");

            // 分别获取消费者匹配规则的串 和 服务提供者过滤规则的串
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();

            //------------------------ 将路由规则串解析为key-value形式 ,key为路由规则匹配项，value为匹配对【包含了匹配项对应的 匹配值集合和不匹配值集合 】--------------------/

            // 解析服务消费者匹配规则
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            // 解析服务提供者过滤规则
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);

            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            this.whenCondition = when;
            this.thenCondition = then;


        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 解析配置的路由规则
     *
     * @param rule
     * @return
     * @throws ParseException
     */
    private static Map<String, MatchPair> parseRule(String rule) throws ParseException {

        // 定义条件映射集合，key：匹配项  value: 匹配对[MatchPair]
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();

        if (StringUtils.isBlank(rule)) {
            return condition;
        }

        // Key-Value pair, stores both match and mismatch conditions，匹配对
        MatchPair pair = null;

        // Multiple values，匹配对中的 匹配值集合/不匹配值集合的临时变量
        Set<String> values = null;

        // 分组正则匹配
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);

        /**
         * 通过 ROUTE_PATTERN 正则匹配 rule ，循环多次查找子串，直到结束
         * 说明：
         * 1 find()方法是部分匹配，是查找输入串中与模式匹配的子串，如果该匹配的串有组还可以使用group()函数。当且仅当输入序列的子序列匹配规则才会返回true，可能可以匹配多个子串
         * 2 matcher.group() 返回匹配到的子字符串
         * 说明：
         * 例子：host = 2.2.2.2 & host != 1.1.1.1 & method = hello
         * 匹配结果：
         * 第一个子序列： host              分组一：""  分组二：host
         * 第二个子序列：= 2.2.2.2          分组一：=   分组二：2.2.2.2
         * 第三个子序列：& host             分组一：&   分组二：host
         * ...
         */
        while (matcher.find()) {

            // 获取匹配组一的匹配结果，即分隔符
            String separator = matcher.group(1);

            // 获取匹配组二的匹配结果，即匹配规则项
            String content = matcher.group(2);


            // 匹配组一的匹配结果为空，则说明匹配的是表达式的开始部分
            if (separator == null || separator.length() == 0) {
                // 创建 MatchPair 对象
                pair = new MatchPair();
                // 存储 <匹配项, MatchPair> 键值对，比如 <host, MatchPair>
                condition.put(content, pair);
            }

            // 如果匹配组一的匹配结果是 '&',说明接下来是一个新的条件
            else if ("&".equals(separator)) {
                // 先尝试从 condition 中获取content对应的MatchPair，不存在则新建并放入condition中
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }

            // 如果分隔符为 '='，那么对应的值就是匹配值
            else if ("=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                // 匹配对中的匹配值集合，先取再放
                values = pair.matches;

                // 将 content 存入到 MatchPair 的 matches 集合中
                values.add(content);
            }

            // 如果分隔符为 '!='，那么对应的值就是不匹配值
            else if ("!=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }
                // 匹配对中的不匹配值集合，先取再放
                values = pair.mismatches;

                // 将 content 存入到 MatchPair 的 mismatches 集合中
                values.add(content);
            }


            // 分隔符为 , 表示某个匹配项有多个值，它们以 ','分隔
            else if (",".equals(separator)) {
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                // 将 content 存入到上一步获取到的 values 中，可能是 matches，也可能是 mismatches
                values.add(content);

                // 分隔符错误
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }


        return condition;
    }

    /**
     * 路由，过滤Invoker
     * 说明：
     * 下面的逻辑有的是服务提供者过滤规则，有的是消费者匹配规则，但是该方法传入的url参数目前都是消费者URL: {@link AbstractDirectory#list(com.alibaba.dubbo.rpc.Invocation)} 和 {@link com.alibaba.dubbo.registry.integration.RegistryDirectory#route(java.util.List, java.lang.String)}
     *
     * @param invokers   Invoker 集合
     * @param url        调用者传入，目前都是消费者URL
     * @param invocation 调用信息
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        // 判空
        if (invokers == null || invokers.isEmpty()) {
            return invokers;
        }

        try {

            /**
             * 优先进行消费者匹配条件匹配，如果匹配失败，说明当前消费者URL不符合匹配规则，直接返回invoker集合，无需继续后面的逻辑。
             * 说明：
             * 消费者 ip：192.168.25.100
             * 路由规则：host = 10.20.125.10 => host = 10.2.12.10   【ip为10.20.125.10的消费者调用ip为10.2.12.10的服务提供者】
             * 结果：当前消费者ip为192.168.25.100，这条路由规则不适用于当前的消费者，直接返回
             *
             */
            if (!matchWhen(url, invocation)) {
                return invokers;
            }

            // 路由过滤后的Invoker结果集
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();

            // 服务提供者地址列表的过滤条件未配置，说明是对指定的消费者禁止服务
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }

            // 遍历Invoker集合，依次使用 服务提供者地址列表的过滤条件 进行匹配，如果匹配则加入到 result 中
            for (Invoker<T> invoker : invokers) {
                // 如果匹配成功，则说明当前Invoker 符合服务提供者匹配规则，加入Invoker结果集
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }

            // 如果 result 非空，则直接返回过滤后的Invoker 集合
            if (!result.isEmpty()) {
                return result;

                // 如果过滤后的Invoker集合为空，判断是否强制执行，如果强制执行，则返回空Invoker集合
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }

        // 走到这里，说明过滤后的Invoker集合为空，并且非强制执行，则原样返回invoker 集合，即表示该条路由规则失效，忽律路由规则
        return invokers;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    /**
     * 比较，按照 priority 降序，按照 url 升序
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(Router o) {
        if (o == null || o.getClass() != ConditionRouter.class) {
            return 1;
        }
        ConditionRouter c = (ConditionRouter) o;
        return this.priority == c.priority ? url.toFullString().compareTo(c.url.toFullString()) : (this.priority > c.priority ? 1 : -1);
    }

    /**
     * 对服务消费者进行匹配，如果匹配失败，直接返回Invoker 列表。如果匹配成功，再对服务提供者进行匹配。
     *
     * @param url
     * @param invocation
     * @return
     */
    boolean matchWhen(URL url, Invocation invocation) {
        /**
         * 服务消费者条件为null或者为空，表示对所有消费方应用，返回true。否则再调用matchCondition方法
         */
        return whenCondition == null || whenCondition.isEmpty() || matchCondition(whenCondition, url, null, invocation);
    }

    /**
     * 对服务提供者进行匹配
     *
     * @param url
     * @param param
     * @return
     */
    private boolean matchThen(URL url, URL param) {
        // 服务提供者条件为 null 或空，表示禁用服务
        return !(thenCondition == null || thenCondition.isEmpty()) && matchCondition(thenCondition, url, param, null);
    }

    /**
     * 匹配条件
     *
     * @param condition
     * @param url
     * @param param
     * @param invocation
     * @return
     */
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {

        // 将服务提供者或消费者url转成 Map
        Map<String, String> sample = url.toMap();

        // 是否匹配
        boolean result = false;

        // 遍历匹配条件集合
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {

            // 获得匹配项名称，如 host,method
            String key = matchPair.getKey();
            // 匹配项的值
            String sampleValue;

            // 如果Invocation不为null，且匹配项名称为 method 或 methods
            if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
                // 从Invocation中获取调用方法名
                sampleValue = invocation.getMethodName();

            } else {

                // 从服务提供者或者消费者URL中获取指定字段值，如 host、application
                sampleValue = sample.get(key);

                // 如果没有匹配项对应的值，则尝试获取default.key的值
                if (sampleValue == null) {
                    sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
                }
            }

            //--------------------------- 条件匹配 ------------------------------------------/

            //  匹配项的值不为空
            if (sampleValue != null) {
                // 调用匹配项关联的MatchPair 的 isMatch 方法进行匹配，只要有一个匹配规则匹配失败，就失败
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    result = true;
                }

                // 匹配项的值为空，说明服务提供者或消费者ULR中不包含该配置项的值
            } else {

                // 无匹配项值，但是有匹配条件 `matches` ，则匹配失败，返回false
                if (!matchPair.getValue().matches.isEmpty()) {
                    return false;
                } else {
                    result = true;
                }
            }

        }
        return result;
    }

    /**
     * 匹配对
     */
    private static final class MatchPair {
        /**
         * 匹配的值集合，待匹配值存在于集合，则说明匹配成功
         */
        final Set<String> matches = new HashSet<String>();
        /**
         * 不匹配的值集合，待匹配值存在于集合，则说明匹配失败
         */
        final Set<String> mismatches = new HashSet<String>();

        /**
         * 判断 value 是否匹配 matches + mismatches
         *
         * @param value
         * @param param
         * @return
         */
        private boolean isMatch(String value, URL param) {

            // 1 只匹配 matches，没有匹配上则说明失败了，返回false
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    // 只要入参被 matches 集合中的任意一个元素匹配到，就匹配成功，返回true
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }

                // 如果所有匹配值都无法匹配到 value，则匹配失败,返回false
                return false;
            }

            // 2 只匹配 mismatches，没有匹配上，则说明成功了，返回true
            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    // 只要入参被 mismatches 集合中的任意一个元素匹配到，就匹配失败，返回false
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }

                // mismatches 集合中所有元素都无法匹配到入参，则匹配成功，返回 true
                return true;
            }

            // 3 匹配 mismatches + matches，优先去匹配 mismatches
            if (!matches.isEmpty() && !mismatches.isEmpty()) {

                // 只要 mismatches 集合中任意一个元素与入参匹配成功，则匹配失败，就立即返回 false
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }

                // 只要 matches 集合中任意一个元素与入参匹配成功，则匹配成功，就立即返回 true
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }

                return false;
            }

            // 4 matches 和 mismatches 均为空，此时返回 false
            return false;
        }


    }

    public static void main(String[] args) throws ParseException {
        String str = "host = 2.2.2.2 & host != 1.1.1.1 & method = hello";

        //   Map<String, MatchPair> stringMatchPairMap = parseRule(str);

        final Matcher matcher = ROUTE_PATTERN.matcher(str);


        int i = matcher.groupCount();

        while (matcher.find()) {

            String group = matcher.group();
            System.out.println("group:" + group);


            String group1 = matcher.group(1);
            String group2 = matcher.group(2);

            System.out.println("group1:" + group1 + "  ##  " + "group2:" + group2);
        }


    }
}
