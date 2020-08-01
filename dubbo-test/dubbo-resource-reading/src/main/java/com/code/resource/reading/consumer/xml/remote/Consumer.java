package com.code.resource.reading.consumer.xml.remote;


import com.code.reading.service.IDemoService;
import com.code.resource.reading.api.DemoService;
import org.junit.Test;
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
public class Consumer {


    /**
     * 引用 关联本地源码的服务提供者
     */
    public static String[] consumerSourcePath = {"xml/consumer-source.xml"};
    /**
     * 引用 非本地源码的服务提供者
     */
    public static String[] consumerNotSourcePath = {"xml/consumer-not-source.xml"};

    /**
     * 多协议
     */
    public static String[] multiProtocolPath = {"xml/multi-protocol-provider.xml"};


    @Test
    public void testLocal() throws IOException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(consumerSourcePath);

        DemoService consumerService = (DemoService) context.getBean("sourceDemoService");
        String hello = consumerService.hello();
        System.out.println(hello);
        System.in.read();
    }

    @Test
    public void testRemote() throws IOException, InterruptedException {

        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(consumerNotSourcePath);

        IDemoService demoService = (IDemoService) context.getBean("remoteDemoService");

        System.out.println("5s 后发起调用!!!");
        Thread.sleep(5000);


        while (true) {

            System.out.println();
            System.out.println();

            String result = demoService.ping("ping");
            System.out.println(result);

            System.out.println();
            System.out.println();
            System.out.println();

            System.out.println("休闲5s继续调用！！！");
            Thread.sleep(5000);

        }
    }
}
