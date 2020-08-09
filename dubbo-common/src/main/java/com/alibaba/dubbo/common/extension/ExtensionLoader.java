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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 用于加载dubbo的拓展点实现
 * <ul>
 * <li>自动注入依赖的扩展点 </li>
 * <li>扩展点的Wrap类自动包装该扩展点现实上 </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);


    //========================================= 类属性，所有ExtensionLoader对象共享 ================================================

    /**
     * dubbo扩展点目录 ，该目录是为了兼容jdk的spi
     */
    private static final String SERVICES_DIRECTORY = "META-INF/services/";
    /**
     * dubbo扩展点目录，主要用于自定义扩展点实现
     */
    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";
    /**
     * dubbo扩展点目录，用于 Dubbo 内部提供的拓展点实现
     */
    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    /**
     * 扩展点实现名的分隔符 正则表达式，多个扩展点名之间使用 ',' 进行分割
     */
    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");


    /**
     * 扩展点加载器集合
     * key: 拓展点接口
     * value: 扩展点加载器。 一个扩展点接口对应一个 扩展点加载器
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    /**
     * 扩展点实现类集合
     * key: 扩展点实现类
     * value: 扩展点实现对象
     * 说明：
     * 一个扩展点通过对应的ExtensionLoader去加载它的具体实现，考虑到性能和资源问题，在加载拓展配置后不会立马进行扩展实现的对象的初始化，而是先把扩展配置存起来。
     * 等到真正使用对应的拓展实现时才进行扩展实现的对象的初始化，初始化后也进行缓存。即：
     * 1 缓存加载的拓展配置
     * 2 缓存创建的拓展实现对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();


    // ==============================  实例属性 ，每个ExtensionLoader对象独有 ====================================================

    /**
     * 扩展点，如：Protocol
     */
    private final Class<?> type;

    /**
     * 扩展点实现工厂，用于向扩展对象中注入依赖属性，一般通过调用 {@link #injectExtension(Object)} 方法进行实现
     */
    private final ExtensionFactory objectFactory;

    /**
     * 扩展点实现类 到 扩展名 的映射
     * 如：
     * dubbo=dubbo=com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol ===> <DubboProtocol,dubbo>
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

    /**
     * 扩展名 到 扩展点实现类 的映射
     * 不包括以下两种类型：
     * 1 自适应扩展实现类，如：AdaptiveExtensionFactory
     * 2 扩展点的Wrapper实现类，如：ProtocolFilterWrapper
     * 如：
     * dubbo=dubbo=com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol ===> <dubbo,DubboProtocol>
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    /**
     * 扩展名 到 @Activate注解 的映射， 如： ContextFilter -> Activate
     */
    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();

    /**
     * 扩展名 到 扩展点实现对象 的映射
     * 如：
     * dubbo=dubbo=com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol ===> <dubbo,Holder<DubboProtocol对象>>
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();

    /**
     * 自适应扩展对象
     * 注意: 一个扩展点最多只能有一个自适应扩展对象，> 1 框架就会报错
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();

    /**
     * 自适应扩展实现类 {@link #getAdaptiveExtensionClass()}
     */
    private volatile Class<?> cachedAdaptiveClass = null;

    /**
     * 扩展点的默认扩展名，通过 {@link SPI} 注解获得
     */
    private String cachedDefaultName;

    /**
     * 创建自适应对象时发生的异常 -> {@link #createAdaptiveExtension()}
     */
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * 扩展点Wrapper实现类集合，如：ProtocolFilterWrapper
     */
    private Set<Class<?>> cachedWrapperClasses;

    /**
     * 扩展名 到 加载对应扩展类发生的异常 的映射
     */
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    /**
     * 构造方法
     * 说明：
     * 1 任意一个扩展点在获取对应的ExtensionLoader时，都会先尝试获取属于它的ExtensionFactory自适应扩展，即 AdaptiveExtensionFactory，
     * 它管理着SpiExtensionFactory和SpringExtensionFactory这两大扩展点工厂，用于调用 {@link #injectExtension(Object)}方法，向扩展实现中注入依赖属性，
     * 需要注意的是，SpiExtensionFactory和SpringExtensionFactory获得对象是不同的，前者获取自使用对象，后者从Spring容器中获取对象。
     * 2 当扩展点是ExtensionFactory时，那么它的对应的ExtensionLoader的objectFactory 属性为null
     *
     * @param type 扩展点
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * 获取扩展点对应的扩展加载器
     *
     * @param type 扩展点
     * @param <T>  泛型
     * @return 扩展加载器
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }

        // 扩展点必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }

        // 扩展点必须标有 @SPI 注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        // 从缓存中获取扩展点对应的 扩展加载器
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);

        // 不存在扩展点对应的扩展加载器，则创建并添加到缓存中
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }

        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * 获得符合自动激活条件的扩展实现对象集合
     * <p>
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url中参数名
     * @param group 过滤分组名
     * @return 被激活的扩展实现对象集合
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {

        // 从url中获取参数值，如：<dubbo:service filter="xx1,xx2"/>，key -> filter 、value -> `xx1,xx2`
        String value = url.getParameter(key);

        // 如果value不为空，就对value进行分割，以逗号为分隔符分隔为一个字符串数组
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * 获得激活条件的扩展实现对象集合
     *
     * @param url    url
     * @param values 激活的扩展名数组，可能为空。如：获取dubbo内置的过滤器时，key=service.filter，url中没有对应的值
     * @param group  过滤分组名
     * @return 被激活的扩展实现对象集合
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {

        // 激活扩展实现对象结果集
        List<T> exts = new ArrayList<T>();

        // 激活的扩展名集合
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);

        //  判断扩展名集合中是否有 '-default' , 如： <dubbo:service filter="-default"/> 代表移出所有默认的过滤器。注意，names是个空的List是符合条件的
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {

            // 获取/刷新 扩展点实现类的集合
            getExtensionClasses();

            /**
             * 遍历cachedActivates (拓展名 到 @Activate 的映射)
             * 1 匹配分组，匹配成功则继续逻辑，否则不处理 加载配置文件时收集到的激活扩展类
             * 2 对激活扩展类进行实例化[初次才会，以后就从缓存中取]
             * 3 判断当前缓存中的激活扩展类是否和传入的激活扩展类冲突，如果有冲突，就忽略缓存中的激活扩展类，以传入的扩展类为主
             */
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {

                // 扩展名
                String name = entry.getKey();
                // Activate
                Activate activate = entry.getValue();

                // 匹配分组，判断Activate注解的group属性值是否包含当前传入的group，包含就符合分组条件
                if (isMatchGroup(group, activate.group())) {

                    // 获取扩展名对应的扩展点实现对象
                    T ext = getExtension(name);

                    // 是否忽略 加载配置文件时收集到的激活扩展类
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {

                        exts.add(ext);
                    }
                }
            }

            // 对扩展对象进行排序（根据注解的before、after、order属性）
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }

        List<T> usrs = new ArrayList<T>();

        // 遍历传入的激活扩展名集合
        for (int i = 0; i < names.size(); i++) {

            // 获取激活扩展名
            String name = names.get(i);

            // 判断是否是 移除激活扩展名，如果是就忽略。 如： <dubbo:service filter="-demo"/>，那么此时demo对应的扩展实现就是属于无效的
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX) && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {

                // 处理 自定义的激活扩展配置在默认的激活扩展前面的情况, 如： <dubbo:service filter="demo,default"/>，那么自定义的demo激活扩展就优先默认的激活扩展。主要是exts中的值变化，前面已经处理了默认的激活扩展(加载配置文件时收集到的激活扩展类)
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {

                    // 获得激活扩展实现对象
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }


        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    /**
     * 匹配分组
     *
     * @param group
     * @param groups
     * @return
     */
    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * 获得指定扩展名的扩展对象
     *
     * @param name 扩展名
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }

        // 如果当前扩展名是 'true'，就获取默认的扩展对象
        if ("true".equals(name)) {
            // 方法简化为 getExtension(cachedDefaultName) , cacheDefaultName的值参见 @SPI注解
            return getDefaultExtension();
        }

        // 从缓存中获得对应的扩展对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();

        // 缓存中没有， 双重检锁获取扩展名对应扩展实现对象
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 缓存中确实没有，就创建扩展名对应的扩展实现对象
                    instance = createExtension(name);
                    // 将扩展实现对象放入缓存中
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * 获取扩展点默认的扩展名对应的扩展实现对象，如果没有在@SPI注解中指定，则返回null。 cachedDefaultName被赋值的动作 {@link #loadExtensionClasses()}
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0 || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获得扩展点的自适应扩展对象
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {

        // 从缓存中获取扩展点对应的自适应扩展对象
        Object instance = cachedAdaptiveInstance.get();

        // 如果缓存未命中，则通过双重检锁获取/创建
        if (instance == null) {
            //  若之前创建的时候没有报错，即之前创建了并且没有抛出异常
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    // 再次尝试从缓存中获取
                    instance = cachedAdaptiveInstance.get();

                    if (instance == null) {
                        try {

                            // 创建自适应拓展对象
                            instance = createAdaptiveExtension();

                            // 放入缓存中
                            cachedAdaptiveInstance.set(instance);

                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }

                //  若之前创建的时候报错，则抛出异常
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 创建扩展名对应的扩展点实现对象并缓存到类属性的集合中
     *
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {

        // 获取扩展名对应的扩展点实现类，先尝试从缓存中取对应的扩展实现类，没有的话就加载配置文件然后再次获取
        Class<?> clazz =
                getExtensionClasses()
                        .get(name);

        // 没有找到扩展名对应的扩展点实现类，则报错
        if (clazz == null) {
            throw findException(name);
        }

        try {

            // 从类属性缓存集合中尝试获取扩展点实现类对应的对象
            T instance = (T) EXTENSION_INSTANCES.get(clazz);

            // 当缓存中没有，就通过反射创建扩展点实现类对象并放入缓存
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }

            // dubbo ioc ，向创建的扩展点实现对象注入依赖属性 [todo dubbo ioc实现，进行setter注入]
            injectExtension(instance);

            // 如果当前扩展点存在 Wrapper类型 的扩展实现类，则创建Wrapper实例 【构造方法会包装传进来的instance 对象】，最终返回的是Wrapper实例。[todo dubbo aop实现]
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    // 将当前 instance 作为参数创建 Wrapper 实例，然后进行 setter注入依赖属性
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }

            return instance;

        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 依赖注入
     *
     * @param instance 扩展实现对象 （注意，可能会是一个Wrapper）
     * @return
     */
    private T injectExtension(T instance) {
        try {

            // 只有ExtensionFactory扩展点对应的ExtensionLoader对象的该属性为null，其它扩展点的ExtensionLoader对象的该属性必然不为null
            if (objectFactory != null) {

                // 反射获得扩展实现对象中的所有方法
                for (Method method : instance.getClass().getMethods()) {

                    // 过滤规则为 ' set开头 + 仅有一个参数 + public ' 的方法
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {

                        /**
                         * 检查方法是否有 @DisableInject 注解，有该注解就忽略依赖注入
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }

                        // 获取setter方法参数类型
                        Class<?> pt = method.getParameterTypes()[0];

                        try {

                            // 获得属性名，如：setXxx -> xxx
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";

                            /**
                             * 通过扩展工厂获得属性值，即 方法参数类型作为扩展点，属性名作为扩展名。
                             * ExtensionFactory的实现有三个,AdaptiveExtensionFactory是对其它两个工厂的管理，getExtension方法的真正调用的是其它两个工厂的方法:
                             *  1）SpringExtensionFactory
                             *   getExtension方法会返回容器中名称为property并且类型为pt的bean对象
                             *  2）SpiExtensionFactory
                             *   getExtension方法会返回类型为pt的自适应拓展对象，因为该方法会校验pt是接口类型并且有@SPI注解，然后pt有拓展类的情况下，就会获取pt的自适应拓展对象，property没用到
                             */
                            Object object = objectFactory.getExtension(pt, property);

                            // 通过反射设置属性值
                            if (object != null) {
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }


    /**
     * 获取扩展点实现类的集合，先从缓存中获取，没有就从配置文件中加载并分类放入缓存。
     * 注意：
     * 很多的方法从缓存中获取不到目标数据时，就会调用该方法。或者有些方法从缓存中获取目标数据之前，也会调用该方法。因此，cachedClasses 中有没有值决定是否加载配置文件。该方法是为了刷新扩展点实现类集合的。
     *
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {

        // 先从缓存中获取
        Map<String, Class<?>> classes = cachedClasses.get();

        // 双重检锁，获取扩展点的扩展实现类集合
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();

                // 再次获取还是没有，就从配置文件中加载扩展实现类然后放入缓存
                if (classes == null) {
                    classes = loadExtensionClasses();

                    // 将 扩展名到扩展点实现类的映射 加入到 cachedClasses 集合中，缓存起来
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 从配置文件中加载扩展点配置
     * 注意：
     * 这个方法会设置很多缓存，但是返回的是： 扩展名到扩展点实现类的映射
     * 说明：
     * 唯一调用该方法的地方 {@link #getExtensionClasses()} 已经加过了锁，无需再次加锁
     *
     * @return
     */
    private Map<String, Class<?>> loadExtensionClasses() {

        //1、 通过@SPI注解获得扩展点的默认扩展名（前提是当前拓展点需要有@SPI注解，其实程序执行到这里type一定是有@SPI注解的，因为在获取扩展点的扩展加载器的时候已经判断了）
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);

        //1.1 如果扩展点的@SPI注解设置了默认值
        if (defaultAnnotation != null) {

            // @SPI注解的值就是扩展点的默认扩展名
            String value = defaultAnnotation.value();

            if ((value = value.trim()).length() > 0) {

                // 对默认扩展名进行分隔处理，以逗号分隔为字符串数组
                String[] names = NAME_SEPARATOR.split(value);

                // 检测 SPI 注解内容是否合法，不合法则抛出异常
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }

                /** 设置默认名称，cachedDefaultName 是用来加载扩展点的默认实现 {@link #getDefaultExtension()} */
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }

            }
        }

        //2、 从配置文件中加载拓展实现类集合，这里分别对应三类文件（1. Dubbo内置的 2. Dubbo自定义 3. JDK SPI）
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        loadDirectory(extensionClasses, DUBBO_DIRECTORY);
        loadDirectory(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * 从配置文件中加载扩展实现类集合
     *
     * @param extensionClasses 扩展名到扩展点实现类的映射
     * @param dir              配置文件目录
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir) {

        // 拼接完整的文件名（相对路径）： 目录 + type全类名
        String fileName = dir + type.getName();

        try {

            Enumeration<java.net.URL> urls;

            // 类加载器
            ClassLoader classLoader = findClassLoader();

            /** 获得文件名对应的所有文件数组（可能同一个文件名在不同的目录结构中，这样就会获取多个文件）,每个文件内容封装到一个java.net.URL中*/
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }

            // 遍历java.net.URL集合
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    // 加载java.net.URL
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }

        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 加载配置文件内容（已经封装成了java.net.URL）
     *
     * @param extensionClasses 扩展类集合
     * @param classLoader      类加载器
     * @param resourceURL      文件内容资源
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {

            // 读取文件内容
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));

            try {

                String line;

                // 一行一行的读取。会跳过当前被注释掉行，例如：#dubbo=xxx
                while ((line = reader.readLine()) != null) {

                    // 如果有#注释，那么ci为0，没有就为-1
                    final int ci = line.indexOf('#');

                    // 在有#注释的情况下，此时line的长度为0
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }

                    // 去除前后端空格，防止自定义扩展点实现时配置不规范
                    line = line.trim();

                    // 没有#注释的情况
                    if (line.length() > 0) {
                        try {

                            /**
                             * 拆分 key=value ，name为拓展名 line为拓展实现类名。注意这里name可能为空,这种情况扩展名会自动生成（因为Dubbo SPI兼容Java SPI）
                             */

                            String name = null;

                            // i > 0，有扩展名； i < 0 没有配置扩展名，即兼容Java SPI
                            int i = line.indexOf('=');

                            if (i > 0) {
                                /** 获取 = 左边的key 即扩展名 */
                                name = line.substring(0, i).trim();
                                /** 获取 = 右边的value 即拓展点的实现的全限定性类名 */
                                line = line.substring(i + 1).trim();
                            }

                            // 加载当前行对应的扩展点配置
                            if (line.length() > 0) {
                                /**
                                 * 1 通过反射，根据名称获取扩展点实现类，并指定初始化
                                 * 2 对扩展实现类进行分类缓存
                                 */
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }

                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }

            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * 对扩展点实现类进行分类缓存
     *
     * @param extensionClasses 扩展实现类集合
     * @param resourceURL      文件内容资源
     * @param clazz            扩展点实现类
     * @param name             扩展名
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        // 判断拓展点实现类，是否实现了当前type接口，没有实现就会报错
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }

        //-------------------------------------- 根据扩展点实现类的类型可分为三大类 ，在进行分类缓存中有优先级，即同一个实现类只能归属到某个分类中 --------------------------------/

        /**
         * 1、自适应扩展类
         * 说明：
         * 当前扩展点实现类是否标注@Adaptive注解，标记的话就是自适应扩展类，需要缓存到 cachedAdaptiveClass 属性中，然后结束逻辑，即不会进行下面的 Wrapper、普通扩展类以及自动激活类逻辑判断。
         */
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            // 一个扩展点有且仅允许一个自适应扩展实现类，如果符合条件就加入到缓存中，否则抛出异常
            if (cachedAdaptiveClass == null) {
                cachedAdaptiveClass = clazz;
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }

            /**
             *  2、Wrapper类型 （该类需要有有一个参数的构造方法，且这个参数类型是当前的扩展点type）
             *  说明：
             *  当前扩展点实现类如果属于Wrapper类，需要缓存到 cachedWrapperClasses 属性集合中，然后结束逻辑，即不会进行下面的 普通扩展类以及自动激活类逻辑判断。
             */
        } else if (isWrapperClass(clazz)) {
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }
            wrappers.add(clazz);


            /**
             * 3、普通的扩展实现类，注意Activate自动激活类从大的方面也属于普通的扩展实现类
             */
        } else {

            // 判断是否有默认的构造方法，没有会抛出异常
            clazz.getConstructor();

            // 未配置扩展名，则自动生成。适用于Java SPI的配置方式（Dubbo SPI 兼容Java SPI） 例如： xxx.yyy.DemoFilter生成的拓展名为demo
            if (name == null || name.length() == 0) {
                // 自动生成扩展名
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            // 对扩展名进行分割处理，dubbo支持配置多个扩展名。如果配置多个扩展名需要以','分割
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {

                // 3.1、 如果当前类标注了@Activate注解实现自动激活，就缓存到 cachedActivates集合。需要注意的是，即使扩展点配置多个，cachedActivates 的key 只取第一个。
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {
                    // 拓展名与 @Activate的映射
                    cachedActivates.put(names[0], activate);
                }

                /**
                 * 3.2、缓存当前扩展点分类到 cachedNames 集合 和 cachedClasses 集合
                 * 说明：
                 *  （1）cachedNames 缓存集合中的数据特点：同一个扩展点实现类对应的扩展名即使在配置多个扩展名的情况下也只取第一个
                 *  （2）cachedClasses 缓存集合的数据特点：同一个扩展点实现类对应的扩展名可能存在多个
                 */
                for (String n : names) {

                    // 缓存扩展类到扩展名的映射
                    if (!cachedNames.containsKey(clazz)) {
                        cachedNames.put(clazz, n);
                    }

                    // 缓存扩展名到扩展类的映射，注意如果在不同的文件中配置同一个扩展点实现，并且扩展名有相同的情况，这时以解析的第一个为准
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    /**
     * 是否属于Wrapper类
     * 标准：
     * clazz的单一有参构造方法的参数类型是 扩展点
     *
     * @param clazz
     * @return
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 生成clazz的扩展名
     *
     * @param clazz
     * @return
     */
    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        // 通过@Extension注解的方式设置拓展名的方式已经废弃
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {
            // 获取clazz的简单名称
            String name = clazz.getSimpleName();
            // 如果名称的命名规则为名称以扩展点名称结尾，就取前部分即可。如：xxx.yyy.DemoFilter -> Demo
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            // 转成小写返回
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * 创建自适应扩展对象
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {

            /**
             *  1 getAdaptiveExtensionClass方法用来获得自适应扩展类【注意，获得的自适应扩展类可能是配置文件中的类，也可能是通过字节码创建的】
             *  2 通过反射创建自适应扩展对象
             *  3 调用injectExtension方法，向创建的自适应拓展对象注入依赖
             */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());

        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获得自适应扩展类，先尝试从配置文件中取自适应扩展类，配置文件中没有符合的就生成自适应扩展代码并编译成目标类
     */
    private Class<?> getAdaptiveExtensionClass() {

        // 刷新扩展点实现类集合
        getExtensionClasses();

        // 缓存中有扩展点的自适应类就直接返回
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }

        // 没有就自动生成自适应拓展类的代码，编译后返回该类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 生成自适应扩展的代码，编译后返回该类
     *
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {

        // 生成自适应拓展实现的代码字符串
        String code = createAdaptiveExtensionClassCode();
        // 获取类加载器
        ClassLoader classLoader = findClassLoader();
        // 获取Compiler自适应扩展实现类
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 动态编译，生成Class
        return compiler.compile(code, classLoader);
    }

    /**
     * 生成自适应扩展实现代码字符串
     *
     * @return
     */
    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();

        //----------------- 1 检查扩展点中方法是否包含 Adaptive注解，要求至少有一个方法被 Adaptive 注解修饰 --------------------/

        // 反射获取扩展点所有方法
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;

        // 遍历方法列表，检测是否标注 Adaptive 注解
        for (Method m : methods) {
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }

        // 若所有方法上都没有Adaptive注解，就抛出异常
        if (!hasAdaptiveAnnotation) {
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        //------------------ 2 生成自适应扩展类的代码字符串，代码生成的顺序与 Java 文件内容顺序一致 ---------------------------/

        // 生成package
        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        // 生成import
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        // 开始生成 class
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");

        //------------------ 3 生成自适应扩展类中的方法，接口中方法可以被 Adaptive 注解修饰，也可以不被修饰，处理方式也不同 -------/

        // 遍历方法列表
        for (Method method : methods) {

            // 方法返回类型
            Class<?> rt = method.getReturnType();
            // 方法参数类型
            Class<?>[] pts = method.getParameterTypes();
            // 方法异常类型
            Class<?>[] ets = method.getExceptionTypes();

            // 尝试获取方法的 Adaptive 注解
            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);

            StringBuilder code = new StringBuilder(512);

            // 3.1 生成没有Adaptive注解的方法代码串。Dubbo不会为没有标注Adaptive注解的方法生成代理逻辑，仅仅生成一句抛出异常代码
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");

                // 3.2 生成有Adaptive注解的方法代码串。核心逻辑就是从方法的参数列表中直接或间接获取配置总线URL，然后结合Adaptive注解值及默认扩展名策略，从URL中得到目前扩展名，然后扩展ExtensionLoader获取扩展名对应的扩展实现对象。
            } else {

                int urlTypeIndex = -1;

                // 遍历方法参数类型数组
                for (int i = 0; i < pts.length; ++i) {
                    // 判断参数类型有没有是URL的，确定URL参数位置
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }

                // urlTypeIndex != -1，表示参数列表中存在 URL类型的参数，即直接获取配置总线URL。如：  <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException 方法
                if (urlTypeIndex != -1) {

                    // 为 URL 类型参数生成判空代码，如：if (arg0 == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");", urlTypeIndex);
                    code.append(s);

                    // 为 URL 类型参数生成赋值代码，形如 URL url = arg0
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }

                // 参数列表中不存在 URL 类型参数，只能间接尝试获取配置总线URL。如：<T> Exporter<T> export(Invoker<T> invoker) throws RpcException 方法
                else {

                    // 目标方法名，这里如果存在就是 getUrl
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                    // 遍历方法的参数类型列表
                    for (int i = 0; i < pts.length; ++i) {

                        // 获取当前方法的参数类型 的 全部方法
                        Method[] ms = pts[i].getMethods();

                        // 判断方法参数对象中是否有 public URL getUrl() 方法
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;

                                // 找到方法参数列表中间接存在URL的参数，则结束寻找逻辑
                                break LBL_PTS;
                            }
                        }
                    }

                    // 如果参数列表中没有一个参数有getUrl方法，则抛出异常
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // 为可返回URL的参数生成判空代码，如：if (arg0 == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");", urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);

                    // 为可返回URL的参数 的getUrl方法返回 的URL生成判空代码，如：if (arg0.getUrl() == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    // 生成赋值语句，形如：URL url = argN.getUrl();
                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                //----------------- 4 获取 Adaptive 注解值 ，Adaptive 注解值 value 类型为 String[]，可填写多个值，默认情况下为空数组 -------------/

                /**
                 *  获取@Adaptive注解的值，如果有值，这些值将作为获取扩展名的key，需要注意，Protocol扩展和其它扩展点是不同的，前者获取扩展名是取协议，后者获取扩展名是取参数的值
                 *  1 普通扩展点，如ProxyFactor： String extName = url.getParameter("proxy", "javassist");
                 *  2 Protocol扩展点： String extName = ( url.getProtocol() == null ? "dubbo" : url.getProtocol() );
                 */
                String[] value = adaptiveAnnotation.value();

                // 如果@Adaptive注解没有指定值，则根据扩展接口名生成。如：SimpleExt -> simple.ext，即将扩展接口名中的大写转小写，并使用'.'把它们连接起来
                if (value.length == 0) {

                    // 获取扩展接口简单名称的字符数组
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);

                    for (int i = 0; i < charArray.length; i++) {

                        // 判断是否大写字母，如果是就使用 '.' 连接，并大写转小写
                        if (Character.isUpperCase(charArray[i])) {

                            if (i != 0) {
                                sb.append(".");
                            }

                            sb.append(Character.toLowerCase(charArray[i]));

                        } else {
                            sb.append(charArray[i]);
                        }

                    }
                    value = new String[]{sb.toString()};
                }

                //-------- 5 检测方法参数列表中是否存在 Invocation 类型的参数 --------------------/

                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {

                    // 参数类型是Invocation
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {

                        // 为 Invocation 类型参数生成判空代码
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);

                        // 生成 String methodName = argN.getMethodName()； 代码，Invocation是调用信息，里面包含调用方法
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                //----------------------- 6 扩展名逻辑决策， @SPI、@Adaptive以及方法含有Invocation类型参数都会影响最终的扩展名 -------------------------/

                // 设置默认拓展名，来源与SPI注解值，默认情况下 SPI注解值为空串，此时cachedDefaultName = null
                String defaultExtName = cachedDefaultName;

                String getNameCode = null;

                /**
                 * 遍历Adaptive 的注解值，用于生成从URL中获取拓展名的代码，最终的扩展名会赋值给 getNameCode 变量。
                 * 注意：
                 * 1 这个循环的遍历顺序是由后向前遍历的，因为Adaptive注解可能配置了多个扩展名，而dubbo获取扩展名的策略是从前往后依次获取，找到即结束，以下代码拼接的时候也是从后往前拼接。
                 * 2 生成的扩展名代码大致有3大类，Adaptive的注解中属性值多小决定了内嵌层级：
                 *（1） String extName = (url.getProtocol() == null ? defaultExtName : url.getProtocol()); 获取协议扩展点的扩展名
                 *（2） String extName = url.getMethodParameter(methodName, Adaptive的注解值, defaultExtName); 获取方法级别的参数值作为扩展名，因为方法的参数列表中含有Invocation调用信息。
                 *（3） String extName = url.getParameter(Adaptive的注解值, defaultExtName); 获取参数值作为扩展名
                 *（4） 如果Adaptive的注解中属性值有多个，就进行嵌套获取。如配置两个，以(3)为例：String extName = url.getParameter(Adaptive的注解值[0],url.getParameter(Adaptive的注解值[1], defaultExtName));
                 * 3  参数如果是protocol，protocol是url主要部分，可通过getProtocol方法直接获取。如果是其他的需要是从URL参数部分获取。两者获取方法不一样。其中参数获取又可分为方法级别参数和非方法级别参数
                 */
                for (int i = value.length - 1; i >= 0; --i) {
                    // 第一次遍历分支
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i])) {

                                // 方法参数列表中有调用信息Invocation参数
                                if (hasInvocation) {
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                } else {
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                                }
                            } else {
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                            }
                        } else {
                            if (!"protocol".equals(value[i])) {

                                // 方法参数列表中有调用信息Invocation参数
                                if (hasInvocation) {
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                } else {
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                                }
                            } else {
                                getNameCode = "url.getProtocol()";
                            }
                        }
                        // 第二次开始都走该分支，用于嵌套获取扩展名
                    } else {
                        if (!"protocol".equals(value[i])) {

                            // 方法参数列表中有调用信息Invocation参数
                            if (hasInvocation) {
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            } else {
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                            }
                        } else {
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                        }
                    }
                }

                // 生成 extName 赋值代码
                code.append("\nString extName = ").append(getNameCode).append(";");

                // 生成 extName 判空代码
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);


                //----------------- 7 生成 获取扩展代码 以及 调用扩展的目标方法 ------------------------/

                // 生成 extension 代码
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);", type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());

                code.append(s);

                // 如果方法返回值类型非void，则生成return语句
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }


                // 生成extension调用目标方法逻辑，形如： extension.方法名(arg0, arg2, ..., argN);
                s = String.format("extension.%s(", method.getName());
                code.append(s);

                // 调用方法的参数拼接，注意和生成方法签名的参数名保持一直
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0) {
                        code.append(", ");
                    }
                    code.append("arg").append(i);
                }
                code.append(");");
            }


            //---------- 8  生成方法签名，包裹方法体内容 -------------------/

            // 生成方法签名，格式：public + 返回值全限定名 + 方法名 +(
            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");

            // 生成方法签名的参数列表
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");

            // 生成异常抛出代码
            if (ets.length > 0) {
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");

            // 包括方法体内容
            codeBuilder.append(code.toString());

            codeBuilder.append("\n}");
        }
        codeBuilder.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}