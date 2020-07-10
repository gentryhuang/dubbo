package com.code.resource.reading.consumer.xml.local;


import com.code.resource.reading.api.DemoService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;

/**
 * XmlProvider
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/04/12
 * <p>
 * desc：
 */
public class ConsumerLocal {

    /**
     * 调试本地服务引用，要使服务提供者和消费者在同一个JVM进程中，即两者配置一起加载到JVM中
     */
    public static String[] consumerPath = {"xml/consumer-injvm.xml"};

    public static void main(String[] args) throws IOException{
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(consumerPath);
        System.out.println("Spring 初始化完成");

        DemoService consumerService = (DemoService) context.getBean("demoService");
        String hello = consumerService.hello();
        System.out.println(hello);
        System.in.read();
    }

}
