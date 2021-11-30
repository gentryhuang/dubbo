package org.apache.dubbo.demo.consumer.mock;

import org.apache.dubbo.demo.DemoService;
import org.springframework.stereotype.Service;

/**
 * DemoServiceMock
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2021/04/13
 * <p>
 * desc：
 */
@Service
public class DemoServiceMock implements DemoService {
    @Override
    public String sayHello(String name) {
        return "mock is running !";
    }
}
