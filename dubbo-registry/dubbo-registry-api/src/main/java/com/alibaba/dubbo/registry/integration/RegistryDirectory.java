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
package com.alibaba.dubbo.registry.integration;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RegistryDirectory，
 * 说明：
 * 1 实现了NotifyListener接口，订阅注册中心的数据，实现监听功能，当注册中心服务配置变更会触发回掉notify方法，用于重新引入服务，即刷新Invoker列表。
 * 2 是基于注册中心的Directory实现类，是一种动态的服务目录
 * 3 想让本地服务目录和注册中心的服务信息保持一致需要做很多事情
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    /**
     * 集群扩展实现 Cluster$Adaptive 对象
     */
    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * 路由工厂扩展实现 RouterFactory$Adaptive对象
     */
    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();

    /**
     * 配置规则工厂实现 ConfiguratorFactory$Adaptive 对象
     */
    private static final ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getAdaptiveExtension();


    /**
     * 注册中心的服务键，目前是 com.alibaba.dubbo.registry.RegistryService
     * <p>
     * 通过 {@link #url} 的 {@link URL#getServiceKey()} 获得
     */
    private final String serviceKey;
    /**
     * 服务类型，如：com.alibaba.dubbo.demo.DemoService
     */
    private final Class<T> serviceType;
    /**
     * 服务消费者 URL 的配置项 Map
     */
    private final Map<String, String> queryMap;
    /**
     * 原始的目录URL
     * 如：zookeeper://192.168.100.1:2181/xxxx
     */
    private final URL directoryUrl;
    /**
     * 服务方法数组
     */
    private final String[] serviceMethods;
    /**
     * 是否引用多分组 - 服务分组概念
     */
    private final boolean multiGroup;
    /**
     * 注册中心的Protocol 对象
     */
    private Protocol protocol;
    /**
     * 注册中心
     */
    private Registry registry;
    /**
     * 是否禁止访问：
     * 1 当没有服务提供者
     * 2 当服务提供者被禁用
     */
    private volatile boolean forbidden = false;

    /**
     * 结合配置规则，重写原始目录URL得到的
     */
    private volatile URL overrideDirectoryUrl;

    /**
     * 配置规则数组
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators;

    /**
     * URL串和服务提供者Invoker的映射，注意：String为没有合并消费端参数的URL串
     * <p>
     * Map<url, Invoker> cache service url to invoker mapping.
     */
    private volatile Map<String, Invoker<T>> urlInvokerMap;

    /**
     * 方法名与服务提供者Invoker集合的映射
     * <p>
     * Map<methodName, Invoker> cache service method to invokers mapping.
     */
    private volatile Map<String, List<Invoker<T>>> methodInvokerMap;

    /**
     * 服务提供者Invoker 的URL集合
     * <p>
     * Set<invokerUrls> cache invokeUrls to invokers mapping.
     */
    private volatile Set<URL> cachedInvokerUrls;


    /**
     * 构造方法
     *
     * @param serviceType
     * @param url
     */
    public RegistryDirectory(Class<T> serviceType, URL url) {
        // 调用父类构造方法
        super(url);
        // 如果服务类型为空，抛出异常
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }

        // Url对应的服务键为空，抛出异常
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }
        // 设置服务类型
        this.serviceType = serviceType;
        // 设置服务键
        this.serviceKey = url.getServiceKey();
        // 设置服务消费者 URL 的配置项 Map
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        // 构造的时候，this.overrideDirectoryUrl == this.directoryUrl
        this.overrideDirectoryUrl = this.directoryUrl = url.setPath(url.getServiceInterface()).clearParameters().addParameters(queryMap).removeParameter(Constants.MONITOR_KEY);
        // 获得分组
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        // 设置多分组标识
        this.multiGroup = group != null && ("*".equals(group) || group.contains(","));
        // 从消费者配置项中获取服务方法串
        String methods = queryMap.get(Constants.METHODS_KEY);
        // 设置服务方法数组
        this.serviceMethods = methods == null ? null : Constants.COMMA_SPLIT_PATTERN.split(methods);
    }

    /**
     * 说明：
     * 将配置规则URL集合转换成对应的配置规则集合，用于控制服务提供者/消费者的url中特性参数，从而实现不同的功能
     * <p>
     * <p>
     * Convert override urls to map for use when re-refer.
     * Send all rules every time, the urls will be reassembled and calculated
     * <p>
     * 将overrideURL转换为map，供重新refer时使用。每次下发全部规则，全部重新组装计算。
     *
     * @param urls 配置参数可能值，每种可能值作用不同：
     *             1.override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules (all of the providers take effect) ## 表示全局规则（对所有的提供者全部生效）
     *             2.override://ip:port...?anyhost=false Special rules (only for a certain provider) ## 特殊规则（只针对某个提供者生效）
     *             3.override:// rule is not supported... ,needs to be calculated by registry itself. ##  不支持override:// 规则，需要注册中心自行计算
     *             4.override://0.0.0.0/ without parameters means clearing the override ##  不带参数,表示清除override
     * @return
     */
    public static List<Configurator> toConfigurators(List<URL> urls) {
        // 配置规则URL集合为空，则忽略
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyList();
        }

        // 创建Configurator 配置规则 集合
        List<Configurator> configurators = new ArrayList<Configurator>(urls.size());

        for (URL url : urls) {
            // 如果协议为 empty:// ，会清空所有配置规则，返回空集合
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }

            // 获取配置规则URL的key-value参数集合
            Map<String, String> override = new HashMap<String, String>(url.getParameters());

            // override 上的 anyhost 可能是自动添加的，为了防止影响下面的判断，需要先删除掉
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            override.remove(Constants.ANYHOST_KEY);

            // 如果配置Url参数部分为空，会清空所有配置规则
            if (override.size() == 0) {
                configurators.clear();
                continue;
            }

            // 由工厂创建Configurator 对象，并添加到 configurators 集合中
            configurators.add(configuratorFactory.getConfigurator(url));
        }

        // 对多个配置规则对象排序
        Collections.sort(configurators);

        return configurators;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    /**
     * 向注册中心发起订阅
     *
     * @param url
     */
    public void subscribe(URL url) {
        // 设置服务消费者URL
        setConsumerUrl(url);
        // 向注册中心发起订阅
        registry.subscribe(url, this);
    }

    /**
     * 销毁
     */
    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }

        // 取消订阅
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
        } catch (Throwable t) {
            logger.warn("unexpeced error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }

        // 标记已经销毁
        super.destroy(); // must be executed after unsubscribing
        try {
            // 销毁所有Invoker
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     * 服务变更时会触发回调该方法，用于刷新Invoker列表
     *
     * @param urls 已注册信息列表，总不为空，含义同{@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}的返回值。
     */
    @Override
    public synchronized void notify(List<URL> urls) {

        // 根据URL的分类或协议，分成三个类别：1 服务提供者URL  2 路由URL 3 配置URL
        List<URL> invokerUrls = new ArrayList<URL>();
        List<URL> routerUrls = new ArrayList<URL>();
        List<URL> configuratorUrls = new ArrayList<URL>();

        // 遍历urls，进行分离
        for (URL url : urls) {

            // 获取协议
            String protocol = url.getProtocol();
            // 获取 category 参数的值，默认是 providers
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);


            // 符合路由规则
            if (Constants.ROUTERS_CATEGORY.equals(category) || Constants.ROUTE_PROTOCOL.equals(protocol)) {
                routerUrls.add(url);

                // 符合配置规则
            } else if (Constants.CONFIGURATORS_CATEGORY.equals(category) || Constants.OVERRIDE_PROTOCOL.equals(protocol)) {
                configuratorUrls.add(url);

                // 符合服务提供者
            } else if (Constants.PROVIDERS_CATEGORY.equals(category)) {
                invokerUrls.add(url);
            } else {
                logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
            }
        }

        // configurators下的数据变更，则将配置规则URL集合转换成对应的配置器集合，用于控制服务提供者/消费者的url中特性参数，从而实现不同的功能
        if (configuratorUrls != null && !configuratorUrls.isEmpty()) {
            this.configurators = toConfigurators(configuratorUrls);
        }

        // routers下的数据变更
        if (routerUrls != null && !routerUrls.isEmpty()) {
            // 将路由URL集合转换成对应的Router集合
            List<Router> routers = toRouters(routerUrls);

            // 如果处理得到的Router非空，调用父类的#setRouters方法，设置路由规则。
            if (routers != null) {
                setRouters(routers);
            }
        }

        // 合并配置规则到directoryUrl,形成overrideDirectoryUrl变量
        List<Configurator> localConfigurators = this.configurators;
        this.overrideDirectoryUrl = directoryUrl;
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {

                // 使用配置规则器 将 配置规则应用到 overrideDirectoryUrl
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }

        // 刷新Invoker列表
        refreshInvoker(invokerUrls);
    }


    /**
     * 转换invokerUrl列表 为Invoker Map集合 的转换规则如下：
     * 1 如果url【invokerUrls】 已经被转换为 invoker ，则不再重新引用，直接从缓存中获取，注意如果 url 中任何一个参数变更也会重新引用
     * 2 如果传入的invoker列表[invokerUrls]不为空，则表示最新的Invoker 列表
     * 3 如果传入的invokerUrl列表为空，则说明只是override规则或者route规则发生改变，需要重新交叉对比，决定是否需要重新引用
     *
     * @param invokerUrls this parameter can't be null
     */
    /**
     * todo 重点：
     * 1 终极目标就是初始化RegistryDirectory的两个属性： urlInvokerMap和methodInvokerMap
     * 2 该方法也是保证RegistryDirectory 随注册中心服务配置变更而变化的关键
     *
     * @param invokerUrls
     */
    private void refreshInvoker(List<URL> invokerUrls) {
        /**
         * 当invokerUrls集合大小为1，并且协议是 empty://，说明所有的服务都已经下线了，即禁用所有服务。如果使用注册中心为 Zookeeper，可参见 {@link ZookeeperRegistry#toUrlsWithEmpty}
         *
         * todo 当订阅URL对应的没有数据变更时（没有数据变更也会创建一个empty协议的URL），不需要重新引入服务。把相关的缓存删除即可，这个是理解错误的，带调试的时候分析为什么之前理解错了？？？？
         */
        if (invokerUrls != null && invokerUrls.size() == 1 && invokerUrls.get(0) != null && Constants.EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // 设置禁止访问，因为没有服务提供者
            this.forbidden = true;
            // 置空 方法名与Invoker集合映射 methodInvokerMap
            this.methodInvokerMap = null;
            // 销毁所有的 服务提供者Invoker 集合
            destroyAllInvokers();


        } else {

            // 设置允许访问
            this.forbidden = false;

            // 引用旧的 urlInvokerMap
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap;

            // 如果传入的 invokerUrls 为空，说明是路由规则或者配置规则发生改变。那么直接使用缓存的服务提供者Invoker 的URL集合
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                invokerUrls.addAll(this.cachedInvokerUrls);

                // 传入的invokerUrls非空，说明服务提供者发生了改变，此时更新 缓存的服务提供者Invoker的URL集合 cachedInvokerUrls
            } else {

                this.cachedInvokerUrls = new HashSet<URL>();

                //缓存invokerUrls列表，便于交叉对比
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }

            // 如果没有 invokerUrls，则直接忽略。【例如：初始是按照 configurators => routers => providers ，那么前两个会出现为空的情况】
            if (invokerUrls.isEmpty()) {
                return;
            }

            // 将变更的URL列表转成 URL串和服务提供者Invoker的映射
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map

            // 将上一步得到的 newUrlInvokerMap 转换成 方法名到Invoker列表的映射
            Map<String, List<Invoker<T>>> newMethodInvokerMap = toMethodInvokers(newUrlInvokerMap); // Change method name to map Invoker Map

            // 如果计算错误【转换错误】，则不进行后续的处理
            if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls.toString()));
                return;
            }

            // 如果引用多分组，那么就按照 method + group 聚合Invoker集合
            this.methodInvokerMap = multiGroup ? toMergeMethodInvokerMap(newMethodInvokerMap) : newMethodInvokerMap;

            //  保存为本地缓存 【 服务提供者Invoker的Map，这个属性中保存的Invoker在待用的时候就会取出，很关键】
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                // 销毁不再使用的 Invoker 集合，避免服务消费者调用已经下线的服务
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    /**
     * 如果引用多分组，那么就按照 method + group 聚合Invoker集合，即method的对应Invoker集合是group对应的Invoker
     *
     * @param methodMap
     * @return
     */
    private Map<String, List<Invoker<T>>> toMergeMethodInvokerMap(Map<String, List<Invoker<T>>> methodMap) {

        // 处理结果，新的methodInvokerMap 字段
        Map<String, List<Invoker<T>>> result = new HashMap<String, List<Invoker<T>>>();

        // 循环map集合，根据 method + group 聚合 Invoker 集合
        for (Map.Entry<String, List<Invoker<T>>> entry : methodMap.entrySet()) {

            // 获取方法名
            String method = entry.getKey();
            // 获取方法名对应的invoker列表
            List<Invoker<T>> invokers = entry.getValue();

            // 按照 group 聚合 Invoker 集合的结果，key：group value：Invoker集合
            Map<String, List<Invoker<T>>> groupMap = new HashMap<String, List<Invoker<T>>>();

            // 遍历Invoker集合
            for (Invoker<T> invoker : invokers) {
                // 获取分组名
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");

                // 以分组名聚合Invoker集合
                List<Invoker<T>> groupInvokers = groupMap.get(group);
                if (groupInvokers == null) {
                    groupInvokers = new ArrayList<Invoker<T>>();
                    groupMap.put(group, groupInvokers);
                }
                groupInvokers.add(invoker);
            }

            // 只有一个就直接使用该分组对应的Invoker集合
            if (groupMap.size() == 1) {
                result.put(method, groupMap.values().iterator().next());

                // 有多个分组话，将每个分组的Invoker集合，经过Cluster处理成一个 Cluster Invoker对象
            } else if (groupMap.size() > 1) {
                List<Invoker<T>> groupInvokers = new ArrayList<Invoker<T>>();
                for (List<Invoker<T>> groupList : groupMap.values()) {
                    groupInvokers.add(cluster.join(new StaticDirectory<T>(groupList)));
                }

                result.put(method, groupInvokers);

                // 没有的话，就使用原来的数据
            } else {
                result.put(method, invokers);
            }
        }

        return result;
    }

    /**
     * 将路由规则 URL 集合，转换成对应的 Router 集合
     *
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private List<Router> toRouters(List<URL> urls) {
        List<Router> routers = new ArrayList<Router>();

        // 路由规则 URL集合 判空
        if (urls == null || urls.isEmpty()) {
            return routers;
        }

        if (urls != null && !urls.isEmpty()) {
            // 遍历路由 URL集合
            for (URL url : urls) {
                // 如果是 empty:// ，忽略。 【一般情况下，所有路由规则被删除时，有且仅有一条协议为 "empty://" 的路由规则 URL】
                if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                    continue;
                }

                // 获得 "router"
                String routerType = url.getParameter(Constants.ROUTER_KEY);
                if (routerType != null && routerType.length() > 0) {
                    url = url.setProtocol(routerType);
                }


                try {
                    // 创建 Router
                    Router router = routerFactory.getRouter(url);
                    if (!routers.contains(router)) {
                        routers.add(router);
                    }
                } catch (Throwable t) {
                    logger.error("convert router url to router error, url: " + url, t);
                }
            }
        }
        return routers;
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     * <p>
     * 将服务提供者URL转为Invoker，即使用protocol.refer方法把url转为Invoker
     *
     * @param urls 变更的URL列表
     * @return invokers URL串映射Invoker的集合Map
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {

        // 变更后的urls对应的 新的 urlInvokerMap 集合
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<String, Invoker<T>>();

        // 若为空直接返回
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }

        // 记录已初始化的服务提供者URL串，即已经处理过的服务提供者URL
        Set<String> keys = new HashSet<String>();

        // 获取消费者配置的协议 [说明：一般情况下，我们不在消费端 <dubbo:reference protocol=""/> 配置服务协议]
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);

        // 循环遍历变更的URL集合，层层处理，最后决定能不能转成 Invoker
        for (URL providerUrl : urls) {

            // 如果消费端配置了协议，则只选择和消费端匹配的协议
            if (queryProtocols != null && queryProtocols.length() > 0) {

                boolean accept = false;

                // 可能配置了多协议
                String[] acceptProtocols = queryProtocols.split(",");

                // 根据消费方protocol过滤不匹配协议，因为Dubbo允许在消费方配置只消费指定协议的服务
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }

                if (!accept) {
                    continue;
                }
            }


            // 如果变更的URL中协议为 empty:// ，则忽略
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }

            // 如果当前应用不支持变更的服务端URL中的协议，则忽略
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() + " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost()
                        + ", supported protocol: " + ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }

            // 合并URL数据
            URL url = mergeUrl(providerUrl);

            // URL字符串
            String key = url.toFullString(); // The parameter urls are sorted

            // 忽略重复推送的服务列表，防止重复引用
            if (keys.contains(key)) { // Repeated url
                continue;
            }
            // 加入到 keys 集合中，为了防止重复
            keys.add(key);


            // 如果服务端 URL 发生改变，则重新引用 【是否改变： 看缓存urlInvokerMap中是否包含变更的URL串，或者缓存urlInvokerMap 为空】

            // 缓存key为没有合并消费端参数的URL，不管消费端如何合并参数，如果服务端URL发生变化，则重新refer
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap;
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);

            // 没有从缓存中获取到，说明服务端URL改变了就需要重新引用，创建Invoker 对象
            if (invoker == null) {
                try {

                    // 判断是否开启 【通过获取配置项 enable 和 disable 的值判断服务是否开启】
                    boolean enabled = true;
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }

                    // 如果开启，则创建 Invoker 对象
                    if (enabled) {
                        /**
                         * 这里以Dubbo 协议为例 （listener->filter-DubboProtocol）
                         * 1 创建客户端与服务端的长连接，然后封装为DubboInvoker,返回
                         * 2 进行filter链包装该DubboInvoker实例，如：ConsumerContextFilter->FutureFilter->MonitorFilter->DubboInvoker
                         * 3 使用InvokerDelegete包装带有Filter链的DubboInvoker实例
                         */
                        invoker = new InvokerDelegate<T>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }

                // 添加到newUrlInvokerMap 中
                if (invoker != null) {
                    // 将InvokerDelegate实例放入newUrlInvokerMap缓存中
                    newUrlInvokerMap.put(key, invoker);
                }

                // 如果变更URL对应的Invoker在缓存中，则直接使用缓存的Invoker 对象，并添加到 newUrlInvokerMap 中
            } else {
                newUrlInvokerMap.put(key, invoker);
            }
        }

        // 情况keys标记集合
        keys.clear();

        // 返回变更URL对应的 InvokerMap
        return newUrlInvokerMap;
    }

    /**
     * 合并URL参数，优先级：配置规则 -> 系统参数 -> 消费端配置 -> 服务端配置
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl 服务端URL
     * @return
     */
    private URL mergeUrl(URL providerUrl) {

        // 将消费端URL配置项合并到服务提供者的URL中
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap);

        // 合并配置规则到 providerUrl中
        List<Configurator> localConfigurators = this.configurators; // local reference
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                // 使用配置规则器 将 配置规则应用到 providerUrl
                providerUrl = configurator.configure(providerUrl);
            }
        }

        // 设置 不检查连接是否成功总是创建Invoker 参数   到 providerUrl中
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false));

        // 仅合并服务提供者参数到 overrideDirectoryUrl 【注意，directoryUrl在 #notify(List<URL> urls) 方法中已经合并过了配置规则 】
        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        // todo 对 1.0 版本的兼容，可不考虑
        if ((providerUrl.getPath() == null || providerUrl.getPath().length() == 0)
                && "dubbo".equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }

        // 返回处理后的新的服务提供者 URL
        return providerUrl;
    }

    /**
     * @param invokers
     * @param method
     * @return
     */
    private List<Invoker<T>> route(List<Invoker<T>> invokers, String method) {
        // 创建Invocation 对象
        Invocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
        // 获得 Router 数组
        List<Router> routers = getRouters();
        // 根据路由规则，筛选Invoker 集合
        if (routers != null) {
            for (Router router : routers) {
                if (router.getUrl() != null) {
                    invokers = router.route(invokers, getConsumerUrl(), invocation);
                }
            }
        }
        return invokers;
    }

    /**
     * 将 invokersMap 转成 方法名到 Invoker 列表的映射
     * <p>
     * Transform the invokers list into a mapping relationship with a method
     *
     * @param invokersMap Invoker Map
     * @return Mapping relation between Invoker and method  方法名与Invoker集合的映射
     */
    private Map<String, List<Invoker<T>>> toMethodInvokers(Map<String, Invoker<T>> invokersMap) {

        // 服务提供者方法名到Invoker集合映射
        Map<String, List<Invoker<T>>> newMethodInvokerMap = new HashMap<String, List<Invoker<T>>>();

        // 记录服务提供者Invoker集合
        // According to the methods classification declared by the provider URL, the methods is compatible with the registry to execute the filtered methods
        List<Invoker<T>> invokersList = new ArrayList<Invoker<T>>();

        // 按照服务提供者URL声明的method分类，兼容注册中心执行路由过滤掉的 methods
        if (invokersMap != null && invokersMap.size() > 0) {
            // 循环每个服务提供者Invoker
            for (Invoker<T> invoker : invokersMap.values()) {

                // 服务提供者URL声明的methods
                String parameter = invoker.getUrl().getParameter(Constants.METHODS_KEY);

                if (parameter != null && parameter.length() > 0) {

                    // 切割 methods 参数值，处理成方法名数组
                    String[] methods = Constants.COMMA_SPLIT_PATTERN.split(parameter);

                    if (methods != null && methods.length > 0) {

                        // 循环每个方法，按照方法名的维度，加入到 methodInvokerMap 中
                        for (String method : methods) {
                            // 当服务提供者的方法为 * 时，代表泛化调用，不处理。
                            if (method != null && method.length() > 0 && !Constants.ANY_VALUE.equals(method)) {

                                // 根据方法名获取Invoker集合
                                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);

                                if (methodInvokers == null) {
                                    methodInvokers = new ArrayList<Invoker<T>>();
                                    newMethodInvokerMap.put(method, methodInvokers);
                                }

                                // 存储 Invoker 到集合中
                                methodInvokers.add(invoker);
                            }
                        }
                    }

                }

                invokersList.add(invoker);
            }
        }

        // 使用路由过滤服务提供者Invoker列表
        List<Invoker<T>> newInvokersList = route(invokersList, null);

        // 添加 newInvokersList 到 newMethodInvokerMap 中，key为 * ，value为 newInvokersList
        newMethodInvokerMap.put(Constants.ANY_VALUE, newInvokersList);

        if (serviceMethods != null && serviceMethods.length > 0) {

            // 遍历服务方法数组，基于每个方法路由，匹配方法对应的Invoker集合
            for (String method : serviceMethods) {
                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                if (methodInvokers == null || methodInvokers.isEmpty()) {
                    methodInvokers = newInvokersList;
                }

                // 方法级别路由
                newMethodInvokerMap.put(method, route(methodInvokers, method));
            }
        }

        // 排序，转成不可变集合
        for (String method : new HashSet<String>(newMethodInvokerMap.keySet())) {
            List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
            Collections.sort(methodInvokers, InvokerComparator.getComparator());
            newMethodInvokerMap.put(method, Collections.unmodifiableList(methodInvokers));
        }

        return Collections.unmodifiableMap(newMethodInvokerMap);
    }

    /**
     * Close all invokers  销毁所有服务提供者Invoker
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            // 遍历 urlInvokerMap，销毁所有服务提供者 Invoker
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }

            // 清空urlInvokerMap
            localUrlInvokerMap.clear();
        }

        // 置空 methodInvokerMap
        methodInvokerMap = null;
    }

    /**
     * 销毁不再使用的 Invoker 集合
     * <p>
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        // 安全处理
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            // 销毁所有服务提供者Invoker
            destroyAllInvokers();
            return;
        }

        // 比较新老集合，确定需要销毁哪些Invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {

            // 获取新生成的 Invoker 集合
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();

            // 遍历老的 <url串, Invoker> 映射表
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {

                // 如果新的集合中不包含老的Invoker，则表示可以把当前老的Invoker删除
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<String>();
                    }

                    // 若不包含，则将老的 Invoker 对应的 url 存入 deleted 列表中
                    deleted.add(entry.getKey());
                }
            }
        }

        // 有需要销毁的Invoker就进行销毁
        if (deleted != null) {

            // 遍历 deleted 集合，并到老的 <url, Invoker> 映射关系表查出 Invoker并销毁
            for (String url : deleted) {
                if (url != null) {
                    // 从老的Invoker集合中移除要销毁的Invoker
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁Invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] faild. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从 Map<String, List<Invoker<T>>> methodInvokerMap 中获取 Invoker 集合
     *
     * @param invocation
     * @return
     */
    @Override
    public List<Invoker<T>> doList(Invocation invocation) {

        // 服务提供者关闭或禁用了，此时抛出 No provider 异常
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION,
                    "No provider available from registry " + getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " + NetUtils.getLocalHost()
                            + " use dubbo version " + Version.getVersion() + ", please check status of providers(disabled, not registered or in blacklist).");
        }


        List<Invoker<T>> invokers = null;

        // 获取Invoker本地缓存 【服务引用的过程 methodInvokerMap 中的值已经有了，并且该值会随着订阅的服务而变化】
        Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap;

        // 获得Invoker集合逻辑
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            // 获得调用的方法名
            String methodName = RpcUtils.getMethodName(invocation);
            // 获得调用的方法参数列表
            Object[] args = RpcUtils.getArguments(invocation);

            if (args != null && args.length > 0 && args[0] != null && (args[0] instanceof String || args[0].getClass().isEnum())) {
                //1 可根据第一个参数枚举，和本身的方法名拼接确定最后的方法名，然后获得Invoker集合方法又回到了根据方法名获取Invoker集合。 这种方式有点奇怪！！！ todo
                invokers = localMethodInvokerMap.get(methodName + args[0]); // The routing can be enumerated according to the first parameter
            }

            if (invokers == null) {
                //2 根据方法名获得Invoker 集合，一般会成功
                invokers = localMethodInvokerMap.get(methodName);
            }

            if (invokers == null) {
                //3 通过通配符 * 获取 Invoker 集合，如 回声探测方法
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }

            if (invokers == null) {
                //4 使用 methodInvokerMap 第一个Invoker
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }

        // 返回目标Invoker集合
        return invokers == null ? new ArrayList<Invoker<T>>(0) : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    /**
     * 判断是否可用
     *
     * @return
     */
    @Override
    public boolean isAvailable() {
        // 如果已经销毁，返回不可用
        if (isDestroyed()) {
            return false;
        }

        // 任意一个Invoker可用，则返回可用
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, List<Invoker<T>>> getMethodInvokerMap() {
        return methodInvokerMap;
    }

    /**
     * 实现 Comparator 接口，Invoker 排序实现类，根据 URL 升序。
     */
    private static class InvokerComparator implements Comparator<Invoker<?>> {

        private static final InvokerComparator comparator = new InvokerComparator();

        private InvokerComparator() {
        }

        public static InvokerComparator getComparator() {
            return comparator;
        }

        /**
         * 根据URL升序
         *
         * @param o1
         * @param o2
         * @return
         */
        @Override
        public int compare(Invoker<?> o1, Invoker<?> o2) {
            return o1.getUrl().toString().compareTo(o2.getUrl().toString());
        }

    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     * Invoker 代理类，主要用于存储注册中心下发的providerUrl
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        /**
         * 服务提供者URL，注意，这是未经过配置合并的URL
         */
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }
}
