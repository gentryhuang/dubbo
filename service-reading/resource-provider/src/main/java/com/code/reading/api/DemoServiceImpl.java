package com.code.reading.api;

import com.code.reading.service.IDemoService;

/**
 * DemoServiceImpl
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/04
 * <p>
 * desc：
 */
public class DemoServiceImpl implements IDemoService {
    @Override
    public String ping(String pg) {
        return "ping " + pg ;
    }
}
