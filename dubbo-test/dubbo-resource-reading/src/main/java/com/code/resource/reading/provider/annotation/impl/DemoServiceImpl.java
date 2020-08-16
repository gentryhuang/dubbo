package com.code.resource.reading.provider.annotation.impl;


import com.alibaba.dubbo.config.annotation.Service;
import com.code.resource.reading.api.DemoService;

/**
 * DemoService
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/04/19
 * <p>
 * desc：
 */
@Service(version = "1.0.0")
public class DemoServiceImpl implements DemoService {

    @Override
    public String hello() {
        return null;
    }
}
