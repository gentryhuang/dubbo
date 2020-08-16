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
package com.code.resource.reading.provider.annotation;

import com.alibaba.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.annotation.PropertySource;

/**
 * MergeProvider
 * <p>
 * Java Config + 注解的方式
 */
public class AnnotationProvider {

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ProviderConfiguration.class);
        context.start();

        Object bean = context.getBean("com.alibaba.dubbo.config.ApplicationConfig#0");

        System.out.println("-----------------------------------------------------------------");

        System.out.println(bean);


        System.in.read();
    }

    /**
     * 配置和注解混合使用
     */
    @Configuration
    @EnableDubbo(scanBasePackages = "com.code.resource.reading.provider.annotation.impl")
    @PropertySource({"classpath:/com/code/resource/reading/provider/annotation/dubbo-provider.properties"})
    //@ImportResource({"classpath:annotation/xml-provider.xml"})
    static public class ProviderConfiguration {
    }
}

