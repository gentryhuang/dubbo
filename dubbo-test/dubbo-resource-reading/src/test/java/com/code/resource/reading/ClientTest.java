package com.code.resource.reading;

import com.code.resource.reading.api.DemoService;
import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;

/**
 * ClientTest
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/06/22
 * <p>
 * desc：
 */
public class ClientTest {

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
    public void testConsumerSourcePath(){


    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(consumerSourcePath);

        DemoService consumerService = (DemoService) context.getBean("sourceDemoService");
        String hello = consumerService.hello();
        System.out.println(hello);
        System.in.read();
    }





}
