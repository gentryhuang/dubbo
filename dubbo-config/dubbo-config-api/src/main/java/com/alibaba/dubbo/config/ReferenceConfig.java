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
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ConsumerModel;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.StaticContext;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.AvailableCluster;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.injvm.InjvmProtocol;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * ReferenceConfig 消费者引用配置类
 *
 * @export
 */
public class ReferenceConfig<T> extends AbstractReferenceConfig {

    private static final long serialVersionUID = -5864351140409987595L;

    /**
     * 自适应 Protocol 拓展实现
     */
    private static final Protocol refprotocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * 自适应 Cluster 拓展实现
     */
    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * 自适应 ProxyFactory 拓展实现
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    /**
     * 服务引用URL数组
     */
    private final List<URL> urls = new ArrayList<URL>();
    /**
     * 服务接口名
     */
    private String interfaceName;
    /**
     * 服务接口
     */
    private Class<?> interfaceClass;
    // client type
    private String client;


    /**
     * 直连服务提供者地址
     * 1 可以是注册中心，也可以是服务提供者
     * 2 可以配置多个，使用 ";" 分割
     */
    private String url;
    // method configs
    private List<MethodConfig> methods;
    // default config
    private ConsumerConfig consumer;
    private String protocol;
    // interface proxy reference
    private transient volatile T ref;
    private transient volatile Invoker<?> invoker;
    private transient volatile boolean initialized;
    private transient volatile boolean destroyed;
    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        appendAnnotation(Reference.class, reference);
    }

    private static void checkAndConvertImplicitConfig(MethodConfig method, Map<String, String> map, Map<Object, Object> attributes) {
        //check config conflict
        if (Boolean.FALSE.equals(method.isReturn()) && (method.getOnreturn() != null || method.getOnthrow() != null)) {
            throw new IllegalStateException("method config error : return attribute must be set true when onreturn or onthrow has been setted.");
        }
        //convert onreturn methodName to Method
        String onReturnMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_RETURN_METHOD_KEY);
        Object onReturnMethod = attributes.get(onReturnMethodKey);
        if (onReturnMethod instanceof String) {
            attributes.put(onReturnMethodKey, getMethodByName(method.getOnreturn().getClass(), onReturnMethod.toString()));
        }
        //convert onthrow methodName to Method
        String onThrowMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_THROW_METHOD_KEY);
        Object onThrowMethod = attributes.get(onThrowMethodKey);
        if (onThrowMethod instanceof String) {
            attributes.put(onThrowMethodKey, getMethodByName(method.getOnthrow().getClass(), onThrowMethod.toString()));
        }
        //convert oninvoke methodName to Method
        String onInvokeMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_INVOKE_METHOD_KEY);
        Object onInvokeMethod = attributes.get(onInvokeMethodKey);
        if (onInvokeMethod instanceof String) {
            attributes.put(onInvokeMethodKey, getMethodByName(method.getOninvoke().getClass(), onInvokeMethod.toString()));
        }
    }

    private static Method getMethodByName(Class<?> clazz, String methodName) {
        try {
            return ReflectUtils.findMethodByMethodName(clazz, methodName);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }


    /**
     * 在服务消费方中，接口并不包含具体的实现逻辑，具体实现都放在服务提供方，但是当我们在调用接口的时候，却能做到与调用本地方法没有区别，原因在于调用方提供了一个代理类，在运行时与该接口绑定，
     * 当接口中的方法被调用时，实际是作用于该代理类上，代理类封装了远程调用的逻辑，把请求参数发送给远程服务提供方，获取结果后再返回.
     */
    public synchronized T get() {
        // 已销毁，不可获得
        if (destroyed) {
            throw new IllegalStateException("Already destroyed!");
        }
        // 若未初始化，调用init()方法进行初始化
        if (ref == null) {
            init();
        }
        // 返回引用服务
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    /**
     * 初始化
     */
    private void init() {
        // 已经初始化过，直接返回
        if (initialized) {
            return;
        }
        initialized = true;
        // 校验接口名非空
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        // 拼接属性配置（环境变量 + .properties 中的属性）到 ConsumerConfig对象
        checkDefault();
        // 拼接属性配置（环境变量 + .properties 中的属性）到ReferenceConfig（自己）
        appendProperties(this);

        // 若未设置 generic 属性，就使用ConsumerConfig的generic属性
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }

        // 是否是泛化引用，就直接设置当前接口为 GenericService
        if (ProtocolUtils.isGeneric(getGeneric())) {
            interfaceClass = GenericService.class;

            // 普通接口的实现
        } else {
            try {
                // 根据接口名，获得对应的接口类
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

            // 校验接口和方法
            checkInterfaceAndMethods(interfaceClass, methods);
        }

        // 直连提供者，第一优先级，通过 -D 参数（系统变量）指定 ，例如 java -Dcom.alibaba.xxx.XxxService=dubbo://localhost:20890
        String resolve = System.getProperty(interfaceName);
        String resolveFile = null;

        // 直连提供者第二优先级，通过文件映射，例如 com.alibaba.xxx.XxxService=dubbo://localhost:20890
        if (resolve == null || resolve.length() == 0) {
            // 从系统属性中获取解析文件路径
            resolveFile = System.getProperty("dubbo.resolve.file");
            if (resolveFile == null || resolveFile.length() == 0) {
                // 默认先加载 ${user.home}/dubbo-resolve.properties 文件，无需配置，自动加载
                File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
                if (userResolveFile.exists()) {
                    // 获取文件绝对路径
                    resolveFile = userResolveFile.getAbsolutePath();
                }
            }
            // 存在resolveFile,则进行文件读取加载
            if (resolveFile != null && resolveFile.length() > 0) {
                Properties properties = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(new File(resolveFile));
                    // 从文件中加载配置
                    properties.load(fis);
                } catch (IOException e) {
                    throw new IllegalStateException("Unload " + resolveFile + ", cause: " + e.getMessage(), e);
                } finally {
                    try {
                        if (null != fis) {
                            fis.close();
                        }
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
                // 根据服务全路径名获取对应的 直连提供者的url
                resolve = properties.getProperty(interfaceName);
            }
        }

        // 设置直连提供者的 url
        if (resolve != null && resolve.length() > 0) {
            url = resolve;
            if (logger.isWarnEnabled()) {
                if (resolveFile != null) {
                    logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
                } else {
                    logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
                }
            }
        }


        // 不通过系统属性指定，就使用配置的直连（在配置的前提下），如：<dubbo:reference id="xxxService" interface="com.alibaba.xxx.XxxService" url="dubbo://localhost:20890" />

        // 尝试从ConsumerConfig 对象中，读取 application,module,registries,monitor 配置对象
        if (consumer != null) {
            if (application == null) {
                application = consumer.getApplication();
            }
            if (module == null) {
                module = consumer.getModule();
            }
            if (registries == null) {
                registries = consumer.getRegistries();
            }
            if (monitor == null) {
                monitor = consumer.getMonitor();
            }
        }
        // 从ModuleConfig 对象中，读取registries,monitor配置对象
        if (module != null) {
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        // 从ApplicationConfig对象中，读取registries,monitor配置对象
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }

        // 校验ApplicationConfig配置
        checkApplication();
        // 校验 Stub和 Mock 相关的配置
        checkStubAndMock(interfaceClass);

        // 创建参数集合map，用于下面创建Dubbo URL
        Map<String, String> map = new HashMap<String, String>();
        // 符合条件的方法对象的属性，主要用来Dubbo事件通知
        Map<Object, Object> attributes = new HashMap<Object, Object>();

        // 将 side，dubbo,timestamp,pid参数，添加到map集合中
        map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }

        // 非泛化服务，设置revision,methods,interface加入到map集合中
        if (!isGeneric()) {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }
            // 获取接口方法列表，并添加到map中
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        map.put(Constants.INTERFACE_KEY, interfaceName);

        // 将各种配置对象中的属性，添加到 map 集合中
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, consumer, Constants.DEFAULT_KEY);
        appendParameters(map, this);

        // 获得服务键，作为前缀 格式：group/interface:version
        String prefix = StringUtils.getServiceKey(map);

        // 将MethodConfig 对象数组中每个MethodConfig中的属性添加到map中
        if (methods != null && !methods.isEmpty()) {
            // 遍历 MethodConfig 列表
            for (MethodConfig method : methods) {
                appendParameters(map, method, method.getName());
                // 当配置了 MethodConfig.retry=false 时，强制禁用重试
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        // 添加重试次数配置 methodName.retries
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                // 将带有@Parameter(attribute=true)配置对象的属性，添加到参数集合中
                appendAttributes(attributes, method, prefix + "." + method.getName());
                // 检查属性集合中的事件通知方法是否正确，若正确，进行转换
                checkAndConvertImplicitConfig(method, map, attributes);
            }
        }

        // 以系统环境变量（DUBBO_IP_TO_REGISTRY）的值作为服务消费者ip地址,没有设置再取主机地址
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);

        // 把attributes集合添加到StaticContext进行缓存，为了以后的事件通知
        StaticContext.getSystemContext().putAll(attributes);

        // 创建Service 代理对象
        ref = createProxy(map);

        // 根据服务名，ReferenceConfig，代理类构建ConsumerModel，并将ConsumerModel存入到ApplicationModel
        ConsumerModel consumerModel = new ConsumerModel(getUniqueServiceName(), this, ref, interfaceClass.getMethods());
        ApplicationModel.initConsumerModel(getUniqueServiceName(), consumerModel);
    }

    /**
     * 创建Service 代理对象
     *
     * @param map 属性集合，包含服务引用配置对象的配置项
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        // 创建URL对象，该对象仅用来 判断是否本地引用。protocol = temp的原因是，已经使用InjvmProtocol#getInjvmProtocol方法获取到了具体协议为InjvmProtocol，不需要再通过protocol属性获取具体协议
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        // 是否本地引用
        final boolean isJvmRefer;
        // isInjvm()方法返回非空，说明配置了injvm配置项，那么就使用本地引用。
        if (isInjvm() == null) {
            // 如果配置了url配置项【直连服务提供者地址】，说明使用直连服务提供者的功能，而不使用本地引用
            if (url != null && url.length() > 0) { // if a url is specified, don't do local reference
                isJvmRefer = false;
                // 调用InjvmProtocol#isInjvmRefer(url)方法， 通过 tempUrl 判断，是否需要本地引用。 即根据url的协议，scope以及injvm等参数检测是否需要本地引用
            } else if (InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl)) {
                isJvmRefer = true;
                // 默认不是
            } else {
                isJvmRefer = false;
            }
            // 通过injvm属性值判断
        } else {
            isJvmRefer = isInjvm().booleanValue();
        }

        // 本地引用 【 todo 注意：本地引用服务时，不是使用服务提供者的URL，而是服务消费者的URL】
        if (isJvmRefer) {

            // ---  todo 为了调试本地引用临时代码，休眠一会 ------
            try {
                Thread.sleep(2000);
            } catch (Exception ex) {

            }
            //------ todo 为了调试本地引用临时代码  ----------

            // 创建服务引用 URL 对象，协议为injvm
            URL url = new URL(Constants.LOCAL_PROTOCOL, NetUtils.LOCALHOST, 0, interfaceClass.getName()).addParameters(map);
            /**
             * 引用服务，返回Invoker 对象【这里返回的就是InjvmInvoker对象】：
             * 此处 Dubbo SPI 自适应的特性的好处就出来了，可以自动根据 URL 参数，获得对应的拓展实现。例如，invoker 传入后，根据 invoker.url 自动获得对应 Protocol 拓展实现为 InjvmProtocol 。
             * 实际上，Protocol 有两个 Wrapper 拓展实现类： ProtocolFilterWrapper、ProtocolListenerWrapper 。
             * 所以，#refer(...) 方法的调用顺序是：Protocol$Adaptive =>ProtocolListenerWrapper=>=> InjvmProtocol 。
             */
            invoker = refprotocol.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }


            /**
             *
             * 1 正常流程，一般为远程引用 【为什么是一般？ 如果我们配置 <dubbo:reference protocol="injvm"> ，实际走的还是本地引用】。一般情况下，如果真的需要本地引用，建议配置scope=local，这样，会更加明确和清晰
             * 2 相比本地引用，远程引用会多做如下几件事：
             * 1）向注册中心订阅，从而发现服务提供者列表
             * 2）启动通信客户端，通过它进行远程调用
             */
        } else {
            // url不为空，表示使用直连方式，可以是服务提供者的地址，也可以是注册中心的地址
            if (url != null && url.length() > 0) {
                // 拆分地址成数组，使用 "；" 分割
                String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
                // 循环数组，添加到 'url' 中
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        // 创建URL对象
                        URL url = URL.valueOf(u);
                        // 路径属性 url.path未设置时，就设置默认路径，缺省使用接口全名 interfaceName
                        if (url.getPath() == null || url.getPath().length() == 0) {
                            url = url.setPath(interfaceName);
                        }
                        // 如果url.protocol = registry时，即是注册中心的地址，在参数url.parameters.refer上带上服务引用的配置参数map集合
                        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                            urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                            // 服务提供者的地址
                        } else {
                            // 合并url
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
                // 没有定义直连，就从注册中心中获取服务提供者
            } else {

                // 加载注册中心 URL 数组
                List<URL> us = loadRegistries(false);
                // 循环数组，添加到 urls 中
                if (us != null && !us.isEmpty()) {
                    for (URL u : us) {

                        // 加载监控中心 URL
                        URL monitorUrl = loadMonitor(u);

                        // 服务引用配置对象 map，带上监控中心的 URL
                        if (monitorUrl != null) {
                            map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                        }

                        // 注册中心的地址，带上服务引用的配置参数集合map，作为 refer 参数添加到注册中心的URL中，并且需要编码。通过这样的方式，注册中心的URL中，包含了服务引用的配置。
                        urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                    }
                }
                // 未配置注册中心，抛出异常
                if (urls.isEmpty()) {
                    throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                }
            }

            // 单个注册中心或服务提供者【如果是服务提供者的话，就是直连的情况，那个refprotocol对应的拓展实现和是注册中心的是不同的】
            if (urls.size() == 1) {
                /**
                 * 引用服务：（以Dubbo协议为例，并且此处的urls中的URL是注册中心地址包裹服务消费者地址）
                 *
                 * Protocol 有两个 Wrapper 拓展实现类： ProtocolFilterWrapper、ProtocolListenerWrapper，所以，#export(...) 方法的调用顺序是：
                 *
                 * Protocol$Adaptive =>ProtocolListenerWrapper=> ProtocolFilterWrapper=> RegistryProtocol
                 * =>
                 * Protocol$Adaptive =>ProtocolListenerWrapper=> ProtocolFilterWrapper => DubboProtocol
                 * 也就是说，这一条大的调用链，包含两条小的调用链。原因是：
                 * 首先，传入的是注册中心的 URL ，通过 Protocol$Adaptive 获取到的是 RegistryProtocol 对象，使用RegistryProtocol的refer构建Invoker实例
                 * 其次，RegistryProtocol 会在其 #refer(...) 方法中，使用服务提供者的 URL ( 即注册中心的 URL 的 refer 参数值)，再次调用 Protocol$Adaptive 获取到的是 DubboProtocol 对象，进行服务暴露。
                 */
                invoker = refprotocol.refer(interfaceClass, urls.get(0));

                // 多个注册中心或多个服务提供者，或者两者的混合。 【todo: 上面的逻辑已经处理了，可以区分是注册中心还是服务提供者，urls列表中的URL如果是注册中心就会使用refer参数拼接消费者URL信息】
            } else {
                // 循环 urls，引用服务，返回Invoker 对象。此时会有多个Invoker对象，需要进行合并。
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;

                for (URL url : urls) {
                    // 引用服务，返回Invoker对象并把服务加入到Invoker集合中
                    invokers.add(refprotocol.refer(interfaceClass, url));
                    // 使用最后一个注册中心的URL
                    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        registryURL = url;
                    }
                }
                // 有注册中心
                if (registryURL != null) {
                    // 对有注册中心的只用 AvailableCluster进行Invoker的合并，这里是AvailableCluster，即服务调用时仅调用第一个可用的Invoker 【todo 集群容错】
                    URL u = registryURL.addParameter(Constants.CLUSTER_KEY, AvailableCluster.NAME);
                    // 创建StaticDirectory实例，并由Cluster对多个Invoker进行合并
                    invoker = cluster.join(new StaticDirectory(u, invokers));
                    // 无注册中心
                } else { // not a registry url
                    invoker = cluster.join(new StaticDirectory(invokers));
                }
            }
        }


        // 是否启动时检查
        Boolean c = check;
        if (c == null && consumer != null) {
            c = consumer.isCheck();
        }
        if (c == null) {
            c = true; // default true
        }
        // 若配置check = true 配置项时，调用Invker#isAvailable()方法，启动时检查，即就是判断当前创建的invoker是否有对应的Exporter
        if (c && !invoker.isAvailable()) {
            throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }

        /**
         * 1 创建Service 代理对象 【该代理对象的内部，会调用 Invoker#invoke(Invocation) 方法，进行 Dubbo 服务的调用】
         * 2 getProxy内部最终得到是一个被StuProxyFactoryWrapper包装后的JavassistProxyFactory
         * 3 把invoker转换成接口代理，代理都会创建InvokerInvocationHandler，InvokerInvocationHandler实现了JDK的InvocationHandler接口，所以服务暴露的Dubbo接口都会委托给这个代理去发起远程调用（injvm协议除外）
         *
         */
        return (T) proxyFactory.getProxy(invoker);
    }

    private void checkDefault() {
        if (consumer == null) {
            consumer = new ConsumerConfig();
        }
        appendProperties(consumer);
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (isGeneric()
                || (getConsumer() != null && getConsumer().isGeneric())) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    @Deprecated
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        checkName("client", client);
        this.client = client;
    }

    @Parameter(excluded = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        this.consumer = consumer;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        return buf.toString();
    }

}
