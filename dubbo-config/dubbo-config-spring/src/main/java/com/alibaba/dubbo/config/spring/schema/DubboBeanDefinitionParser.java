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
package com.alibaba.dubbo.config.spring.schema;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.ArgumentConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.MethodConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.Protocol;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    /**
     * 标签元素对应的对象类
     */
    private final Class<?> beanClass;
    /**
     * 是否需要Bean的 id 属性
     */
    private final boolean required;

    /**
     * @param beanClass Bean 对象的类
     * @param required  是否需要在Bean对象的编号（id）不存在时自动生成编号。无需被其他应用引用的配置对象，无需自动生成编号。 eg：<dubbo:reference/>
     */
    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    /**
     * @param element       标签对应的DOM
     * @param parserContext spring 解析上下文（包含了XmlReaderContext[包含了spring.handler文件及内容]和Bean定义解析代理BeanDefinitionParserDelegate）
     * @param beanClass     标签对应的配置类
     * @param required
     * @return 标签对应的配置类的Bean定义
     */
    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        // 生成Spring的Bean定义，指定beanClass交给Spring反射创建实例
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        /**
         * 设置Bean初始化方式：
         * 引用缺省是延迟初始化的，只有引用被注入到其它Bean或者getBean() 获取才会初始化。如果需要饥饿加载，即没有人引用也立即生成动态代理，
         * 可以配置： <dubbo:reference init="true"/>
         */
        beanDefinition.setLazyInit(false);

        //--- 确保Spring 容器中没有重复的Bean定义 开始  ---/

        // 解析标签对象的id，若不存在则进行生成id。需要注意的是：不同的配置对象，会存在不同
        String id = element.getAttribute("id");
        if ((id == null || id.length() == 0) && required) {

            // --- 确定Bean定义的唯一id start --- /
            String generatedBeanName = element.getAttribute("name");
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                if (ProtocolConfig.class.equals(beanClass)) {
                    generatedBeanName = "dubbo";
                } else {
                    generatedBeanName = element.getAttribute("interface");
                }
            }
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                generatedBeanName = beanClass.getName();
            }
            id = generatedBeanName;
            int counter = 2;
            // 检查Bean定义注册【DefaultListableBeanFactory，Bean的工厂，以Map<String, BeanDefinition> 形式存储bean的定义：key[bean的名称 id]:value[Bean的定义]】中是否存在 标识id，存在就通过自增序列继续处理id,使其唯一
            while (parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter++);
            }
        }
        // --- 确定Bean定义唯一id end --/

        if (id != null && id.length() > 0) {
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            // 把标签对应的配置类的Bean定义注册到Spring
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            // 为Bean追加属性
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }

        if (ProtocolConfig.class.equals(beanClass)) {
            /**
             * 以下代码逻辑需要满足：
             * 顺序需要这样：
             * 1 <dubbo:service interface="com.xxx.xxxService protocol="dubbo" ref="xxxServiceImpl"/>
             * 2 <dubbo:protocol id ="dubbo" name="dubbo" port="20880"/>
             */
            // 获取Bean注册表中中所有的Bean定义id
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                // 根据id获取Bean定义
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                // 获取当前Bean定义的属性对象集合，并尝试获取属性名为protocol的属性对象 [属性对象中包含属性名，属性值等]
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                if (property != null) {
                    Object value = property.getValue();
                    // 如果当前Bean定义的属性满足条件，就更新该Bean属性名为protocol的属性对象，设置为名称为id的RuntimeBeanReference对象
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
        } else if (ServiceBean.class.equals(beanClass)) {
            // 如果<dubbo:service>配置了class属性，那么为具体class配置的类创建Bean定义，并且把该定义注入到Service的 ref属性。一般不这么使用。
            // eg: <dubbo:service interface="com.alibaba.dubbo.demo.DemoService class="com.alibaba.dubbo.demo.provider.DemoServiceImpl"/>
            String className = element.getAttribute("class");
            if (className != null && className.length() > 0) {
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                parseProperties(element.getChildNodes(), classDefinition);
                // 设置service标签对应的ref属性，相当于设置 <dubbo:service ref=""/>属性
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        } else if (ProviderConfig.class.equals(beanClass)) {
            // 解析 <dubbo:provider/> 的内嵌子元素<dubbo:service/>
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            // 解析 <dubbo:consumer/> 的内嵌子元素<dubbo:reference/>
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }

        //-------- 循环Bean对象的setter方法，将属性赋值到Bean对象

        // 用来保存已解析的属性集合
        Set<String> props = new HashSet<String>();
        ManagedMap parameters = null; // 专门存放<dubbo:parameters/> 标签下子标签属性信息。最后都设置到Bean定义中

        // 循环Bean对象的setter方法 (根据标签对应的配置类型中的属性去解析标签)
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            // 选择只有一个参数的public以set为前缀的方法
            if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(setter.getModifiers()) && setter.getParameterTypes().length == 1) {
                // 获取方法的参数类型
                Class<?> type = setter.getParameterTypes()[0];
                // 提取set对应的属性名字，eg: setTimeout->timeout,setBeanName->bean-name
                String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), "-");
                // 保存到属性集合中
                props.add(property);

                // public && getter/is && 属性类型 统一
                Method getter = null;
                try {
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try { // 没有setter对应的getter方法，尝试获取is方法。is方法在功能上是同getter
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                    }
                }
                // 校验是否有对应属性的getter/is前缀方法，没有就跳过
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {
                    continue;
                }

                // 解析 <dubbo:parameters/>
                if ("parameters".equals(property)) {
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                    // 解析 <dubbo:method/>
                } else if ("methods".equals(property)) {
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                    // 解析 <dubbo:argument/>
                } else if ("arguments".equals(property)) {
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    // 判断标签中是否配置了Bean定义中该属性 [标签中配置了才会设置到BeanDefinition中]
                    String value = element.getAttribute(property);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            // 标签中配置了 registry=N/A,不想注册到注册中心的情况，就设置该Bean定义的registry属性对象的地址字段为N/A
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);
                                // 多注册中心情况
                            } else if ("registry".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("registries", value, beanDefinition, parserContext);
                                // 多服务提供者情况
                            } else if ("provider".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("providers", value, beanDefinition, parserContext);
                                // 多协议情况
                            } else if ("protocol".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("protocols", value, beanDefinition, parserContext);
                            } else {
                                Object reference;
                                // 处理属性类型为基本类型的情况
                                if (isPrimitive(type)) {
                                    // 兼容性处理【一些设置了但是意义不大的属性就把值设置为null】
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd
                                        value = null;
                                    }
                                    reference = value;

                                    // 处理在<dubbo:provider/> 或者 <dubbo:service/> 上定义了 protocol 属性的兼容性
                                } else if ("protocol".equals(property)
                                        && ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)
                                        && (!parserContext.getRegistry().containsBeanDefinition(value)
                                        || !ProtocolConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) {

                                    // 目前 <dubbo:provider protocol=""/> 推荐独立成 <dubbo:protocol/>
                                    if ("dubbo:provider".equals(element.getTagName())) {
                                        logger.warn("Recommended replace <dubbo:provider protocol=\"" + value + "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
                                    }
                                    // backward compatibility
                                    ProtocolConfig protocol = new ProtocolConfig();
                                    protocol.setName(value);
                                    reference = protocol;

                                    //------- 事件通知: 在调用前，调用后，出现异常，会触发oninvoke，onreturn,onthrow三个事件，可以配置当事件发生时，通知哪个类的哪个方法  ------//
                              /*
                              // 格式：实现Bean.方法
                              <bean id="demoCallBack" class = "com.alibaba.dubbo.callback.implicit.NofifyImpl"/>
                              <dubbo:reference id = "demoService" interface="com.alibaba.dubbo.IDemoService">
                                  <dubbo:method name="get" onreturn="demoCallBack.xxxMethod" onthrow="demoCallBack.xMethod"/>
                              </dubbo:reference>
                               */

                                    // 处理 onreturn 属性
                                } else if ("onreturn".equals(property)) {
                                    // 按照 . 拆分
                                    int index = value.lastIndexOf(".");
                                    // 获取实例
                                    String returnRef = value.substring(0, index);
                                    // 获取实例的方法
                                    String returnMethod = value.substring(index + 1);
                                    // 创建 RuntimeBeanReference，指向回调的对象
                                    reference = new RuntimeBeanReference(returnRef);
                                    // 设置 onreturnMethod 到 BeanDefinition 的属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
                                    // 处理 onthrow 属性
                                } else if ("onthrow".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    // 创建 RuntimeBeanReference，指向回调的对象
                                    reference = new RuntimeBeanReference(throwRef);
                                    // 设置 onthrowMethod 到 BeanDefinition 的属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                    // 处理oninvoke 属性
                                } else if ("oninvoke".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String invokeRef = value.substring(0, index);
                                    String invokeRefMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(invokeRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("oninvokeMethod", invokeRefMethod);
                                    //--------- 事件通知        --------------/
                                } else {
                                    // ref 对应的Bean 必须是单例的 【该Bean 已经在Spring的Bean 定义中】
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    // 创建RuntimeBeanReference
                                    reference = new RuntimeBeanReference(value);
                                }
                                // 设置Bean定义的属性
                                beanDefinition.getPropertyValues().addPropertyValue(property, reference);
                            }
                        }
                    }
                }
            }
        }

        // 将XML元素没有遍历到的属性添加到Map集合中，然后整个集合加入到Bean定义的parameters属性中（一般这种情况很少，这种情况是针对用户自定义的属性，不是Dubbo Schema 约定好的）
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class  // isPrimitive()是否是原始类型即基类，定义一个类时没有使用extends，则这个类直接继承Object类。
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    /**
     * 解析多指向的情况，如多注册中心，多协议等等（一个属性字段设置一个List集合）
     *
     * @param property       属性
     * @param value          属性值
     * @param beanDefinition Bean定义
     * @param parserContext  Spring 解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition,
                                      ParserContext parserContext) {
        String[] values = value.split("\\s*[,]+\\s*");
        ManagedList list = null;
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            if (v != null && v.length() > 0) {
                if (list == null) {
                    list = new ManagedList();
                }
                list.add(new RuntimeBeanReference(v));
            }
        }
        beanDefinition.getPropertyValues().addPropertyValue(property, list);
    }

    /**
     * 解析内嵌的标签
     *
     * @param element        父xml元素
     * @param parserContext  Spring解析上下文
     * @param beanClass      内嵌子元素的Bean类
     * @param required       是否需要Bean的id属性
     * @param tag            子元素标签
     * @param property       父Bean对象在子元素中的属性名
     * @param ref            父Bean的id
     * @param beanDefinition 父Bean定义对象
     */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) {
                        if (first) {
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (isDefault == null || isDefault.length() == 0) {
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        // 解析子元素，创建BeanDefinition 对象
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        // 设置子BeanDefinition的指向，指向父BeanDefinition
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析 <dubbo:service class="xxx"/> 情况下内涵的<property/>
     *
     * @param nodeList       子元素数组
     * @param beanDefinition Bean定义对象
     */
    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                // 只解析<property/>标签
                if (node instanceof Element) {
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {
                        String name = ((Element) node).getAttribute("name");
                        // 优先使用value属性，其次使用ref属性
                        if (name != null && name.length() > 0) {
                            String value = ((Element) node).getAttribute("value");
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {
                                // 属性不全，抛出异常
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析 <dubbo:parameters/>
     *
     * @param nodeList       子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @return 参数集合
     */
    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                // 只解析 <dubbo:parameter/>
                if (node instanceof Element) {
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        // 添加到参数集合
                        String key = ((Element) node).getAttribute("key");
                        String value = ((Element) node).getAttribute("value");
                        // todo <dubbo:parameter hide=""/> 用途
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            key = Constants.HIDE_KEY_PREFIX + key;
                        }
                        parameters.put(key, new TypedStringValue(value, String.class));
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    /**
     * 解析 <dubbo:method/>标签
     *
     * @param id             Bean的id属性
     * @param nodeList       子元素节点数组
     * @param beanDefinition Bean定义对象
     * @param parserContext  Spring 解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    // 只解析 <dubbo:method/>标签
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {
                        String methodName = element.getAttribute("name");
                        if (methodName == null || methodName.length() == 0) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 解析<dubbo:method/> 创建BeanDefinition
                        BeanDefinition methodBeanDefinition = parse(((Element) node),
                                parserContext, MethodConfig.class, false);
                        String name = id + "." + methodName;
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);
                        // 把子标签对应的BeanDefinition都放到List中
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            if (methods != null) {
                // 一次给父Bean 的 methods属性 设置一个子节点的BeanDefinition集合
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }

    /**
     * 解析 <dubbo:argument/>标签
     *
     * @param id             Bean的id属性
     * @param nodeList       子元素节点数组
     * @param beanDefinition Bean定义对象
     * @param parserContext  Spring 解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    // 只解析<dubbo:argument/>
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        BeanDefinition argumentBeanDefinition = parse(((Element) node),
                                parserContext, ArgumentConfig.class, false);
                        String name = id + "." + argumentIndex;
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);
                        // 子Bean加入到数组中
                        arguments.add(argumentBeanDefinitionHolder);
                    }
                }
            }
            if (arguments != null) {
                // 设置父Bean的methods属性
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

    /**
     * @param element       标签元素对象
     * @param parserContext 解析上下文（包含了XmlReaderContext[包含了spring.handler文件及内容]和Bean定义解析代理BeanDefinitionParserDelegate）
     * @return
     */
    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, required);
    }

}
