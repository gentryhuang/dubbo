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
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.registry.support.ProviderConsumerRegTable;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.alibaba.dubbo.common.Constants.ACCEPT_FOREIGN_IP;
import static com.alibaba.dubbo.common.Constants.QOS_ENABLE;
import static com.alibaba.dubbo.common.Constants.QOS_PORT;
import static com.alibaba.dubbo.common.Constants.VALIDATION_KEY;

/**
 * RegistryProtocol
 */
public class RegistryProtocol implements Protocol {

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    /**
     * 单例，在dubbo SPI中，被初始化，有且仅有一次。
     */
    private static RegistryProtocol INSTANCE;

    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<URL, NotifyListener>();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    /**
     * 绑定关系集合，key是服务提供者的URL字符串形式。用于解决rmi 重复暴露端口冲突的问题，已经暴露过的服务不再重新暴露
     */
    private final Map<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<String, ExporterChangeableWrapper<?>>();
    private Cluster cluster;
    /**
     * Protocol 自适应拓展实现类，通过Dubbo SPI自动注入
     */
    private Protocol protocol;
    /**
     * RegistryFactory 自适应拓展实现类，通过Dubbo SPI自动注入
     */
    private RegistryFactory registryFactory;
    private ProxyFactory proxyFactory;

    public RegistryProtocol() {
        INSTANCE = this;
    }

    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(Constants.REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (params != null && !params.isEmpty()) {
            List<String> filteredKeys = new ArrayList<String>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry != null && entry.getKey() != null && entry.getKey().startsWith(Constants.HIDE_KEY_PREFIX)) {
                    filteredKeys.add(entry.getKey());
                }
            }
            return filteredKeys.toArray(new String[filteredKeys.size()]);
        } else {
            return new String[]{};
        }
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    public void register(URL registryUrl, URL registedProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.register(registedProviderUrl);
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        //export invoker
        // 暴露服务 【此处Local指的是，本地启动服务(不同协议会启动不动的服务Server，如：NettyServer，tomcat等)，打开端口，但是不包括向注册中心注册服务】
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker);
        // 获得注册中心 URL
        URL registryUrl = getRegistryUrl(originInvoker);

        //registry provider  获得（创建）注册中心对象
        final Registry registry = getRegistry(originInvoker);
        // 获得真正要注册到注册中心的URL(服务提供者URL去除无用信息）
        final URL registeredProviderUrl = getRegisteredProviderUrl(originInvoker);

        //to judge to delay publish whether or not  // 服务提供者URL参数项 register ,服务提供者是否注册到配置中心。默认是true
        boolean register = registeredProviderUrl.getParameter("register", true);

        // 向本地注册表中记录服务提供者信息（包含服务对应的注册中心地址）
        ProviderConsumerRegTable.registerProvider(originInvoker, registryUrl, registeredProviderUrl);

        // 向注册中心注册服务提供者（自己）
        if (register) {
            // 将服务提供者地址注册写入到注册中心【如：使用zk会先创建服务提供者的节点路径】
            register(registryUrl, registeredProviderUrl);
            // 标记向本地注册表已经注册了服务提供者
            ProviderConsumerRegTable.getProviderWrapper(originInvoker).setReg(true);
        }

        // 使用OverrideListener 对象，订阅配置规则（todo 集群容错）
        // Subscribe the override data
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call the same service. Because the subscribed is cached key with the name of the service, it causes the subscription information to cover.

        // 1）根据registeredProviderUrl来获取overrideSubscribeUrl 【provider://...?...&category=configurators&check=false】
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(registeredProviderUrl);
        // 2) 创建OverrideListener
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        // 3）将订阅放入缓存
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);
        // 4）监听服务接口下configurators节点，实现真正的订阅与通知
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<T>(exporter, originInvoker, overrideSubscribeUrl, registeredProviderUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker) {
        // 获得在 bounds 缓存中的key【就是生成key的逻辑，也是服务提供者暴露地址,即从Invoker的URL中Map属性集合中获取key为'export'的服务提供者暴露地址，该地址要写到注册中心上】
        String key = getCacheKey(originInvoker);
        // 从 bounds 缓存中获得，是否存在已经暴露过的服务
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            synchronized (bounds) {
                exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
                // 未暴露过，进行暴露服务
                if (exporter == null) {
                    /**
                     *1 创建InvokerDelegete 对象
                     *2 InvokerDelegete 继承了 InvokerWrapper类，增加了getInvoker方法，获取非InvokerDelegete的Invoker对象，
                     *  通过getInvoker方法可以看出来，可能会存在InvokerDelete.invoker也是InvokerDelegete类型的情况
                     */
                    final Invoker<?> invokerDelegete = new InvokerDelegete<T>(originInvoker, getProviderUrl(originInvoker));
                    /**
                     * 使用某个协议将InvokerDelegete 转换成Exporter
                     * 1 使用Protocol协议暴露服务并创建ExporterChangeableWrapper 对象 （构造参数： Exporter,Invoker,这样Invoker和Exporter就形成了绑定关系）
                     * 2 具体调用哪个协议的export方法，看Dubbo SPI选择哪个，就调用对应协议的XXXProtocol#export(Invoker)
                     * 3 同样有执行链
                     */
                    exporter = new ExporterChangeableWrapper<T>((Exporter<T>) protocol.export(invokerDelegete), originInvoker);
                    // 添加到bounds
                    bounds.put(key, exporter);
                }
            }
        }
        return exporter;
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> void doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
        } else {
            final Invoker<T> invokerDelegete = new InvokerDelegete<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegete));
        }
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryFactory.getRegistry(registryUrl);
    }

    /**
     * 获得注册中心的URL，这是加载注册中心URL的反向流程
     *
     * @param originInvoker
     * @return
     */
    private URL getRegistryUrl(Invoker<?> originInvoker) {
        // 拿到注册中心的URL
        URL registryUrl = originInvoker.getUrl();
        // 如果URL的协议是 registry ，那么就从参数中尝试取出registry的值，这个值就是注册中心真正的协议，然后将它设置为注册中心的协议，同时移除参数里面registry参数
        if (Constants.REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            // 从URL中取不到registry,就使用默认值dubbo
            String protocol = registryUrl.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_DIRECTORY);
            // 设置真正的协议，并移除参数中的registry参数
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(Constants.REGISTRY_KEY);
        }
        return registryUrl;
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param originInvoker
     * @return
     */
    private URL getRegisteredProviderUrl(final Invoker<?> originInvoker) {
        // 从注册中心的URL中获取 export 参数的值，即服务提供这URL
        URL providerUrl = getProviderUrl(originInvoker);
        // 移除多余的参数，因为这些参数注册到注册中心没有实际的用途，这样可以减轻ZK的压力
        return providerUrl.removeParameters(getFilteredKeys(providerUrl)) // 移除 .开头的的参数
                .removeParameter(Constants.MONITOR_KEY) // monitor
                .removeParameter(Constants.BIND_IP_KEY) // bind.ip
                .removeParameter(Constants.BIND_PORT_KEY) // bind.port
                .removeParameter(QOS_ENABLE) // qos.enable
                .removeParameter(QOS_PORT) // qos.port
                .removeParameter(ACCEPT_FOREIGN_IP) // qos.accept.foreign.ip
                .removeParameter(VALIDATION_KEY); // validation
    }

    private URL getSubscribedOverrideUrl(URL registedProviderUrl) {
        return registedProviderUrl.setProtocol(Constants.PROVIDER_PROTOCOL)
                .addParameters(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY,
                        Constants.CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param origininvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> origininvoker) {
        String export = origininvoker.getUrl().getParameterAndDecoded(Constants.EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + origininvoker.getUrl());
        }

        URL providerUrl = URL.valueOf(export);
        return providerUrl;
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        url = url.setProtocol(url.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_REGISTRY)).removeParameter(Constants.REGISTRY_KEY);
        Registry registry = registryFactory.getRegistry(url);
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        String group = qs.get(Constants.GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((Constants.COMMA_SPLIT_PATTERN.split(group)).length > 1
                    || "*".equals(group)) {
                return doRefer(getMergeableCluster(), registry, type, url);
            }
        }
        return doRefer(cluster, registry, type, url);
    }

    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }

    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());
        URL subscribeUrl = new URL(Constants.CONSUMER_PROTOCOL, parameters.remove(Constants.REGISTER_IP_KEY), 0, type.getName(), parameters);
        if (!Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            registry.register(subscribeUrl.addParameters(Constants.CATEGORY_KEY, Constants.CONSUMERS_CATEGORY,
                    Constants.CHECK_KEY, String.valueOf(false)));
        }
        directory.subscribe(subscribeUrl.addParameter(Constants.CATEGORY_KEY,
                Constants.PROVIDERS_CATEGORY
                        + "," + Constants.CONFIGURATORS_CATEGORY
                        + "," + Constants.ROUTERS_CATEGORY));

        Invoker invoker = cluster.join(directory);
        ProviderConsumerRegTable.registerConsumer(invoker, url, subscribeUrl, directory);
        return invoker;
    }

    /**
     * Invoker 销毁时注销端口和map【bounds】中服务实例等资源
     */
    @Override
    public void destroy() {
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();
    }

    public static class InvokerDelegete<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegete(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegete) {
                return ((InvokerDelegete<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {

        private final URL subscribeUrl;
        private final Invoker originInvoker;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information , is always not empty, The meaning is the same as the return value of {@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);
            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl);
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);
            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            List<Configurator> configurators = RegistryDirectory.toConfigurators(matchedUrls);

            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegete) {
                invoker = ((InvokerDelegete<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            URL newUrl = getConfigedInvokerUrl(configurators, originUrl);
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.doChangeLocalExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl + ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(Constants.CATEGORY_KEY) == null
                        && Constants.OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }

        //Merge the urls of configurators
        private URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
            return url;
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter exported by the protocol, and can modify the relationship at the time of override.
     * <p>
     * <p>
     * Exporter可变的包装器，建立Invoker和Exporter的绑定关系
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        /**
         * 原Invoker 对象
         */
        private final Invoker<T> originInvoker;
        /**
         * 暴露的Exporter 对象
         */
        private Exporter<T> exporter;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            // Invoker销毁时注销map中服务实例等资源
            bounds.remove(key);
            exporter.unexport();
        }
    }

    /**
     * 可销毁的Exporter
     *
     * @param <T>
     */
    static private class DestroyableExporter<T> implements Exporter<T> {

        public static final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));
        /**
         * 暴露的Exporter 对象
         */
        private Exporter<T> exporter;
        /**
         * 原Invoker 对象
         */
        private Invoker<T> originInvoker;
        private URL subscribeUrl;
        private URL registerUrl;

        public DestroyableExporter(Exporter<T> exporter, Invoker<T> originInvoker, URL subscribeUrl, URL registerUrl) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
            this.subscribeUrl = subscribeUrl;
            this.registerUrl = registerUrl;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            Registry registry = RegistryProtocol.INSTANCE.getRegistry(originInvoker);
            try {
                //移除已经注册的元数据
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                // 去掉订阅配置的监听器
                NotifyListener listener = RegistryProtocol.INSTANCE.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        int timeout = ConfigUtils.getServerShutdownTimeout();
                        if (timeout > 0) {
                            logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. Usually, this is called when you use dubbo API");
                            Thread.sleep(timeout);
                        }
                        exporter.unexport();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            });
        }
    }
}
