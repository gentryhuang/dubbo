package com.code.reading.spi.impl;

import com.code.reading.spi.Command;
import org.apache.dubbo.common.extension.Adaptive;

/**
 * AdaptiveCommand
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/10
 * <p>
 * desc：
 */
@Adaptive
public class AdaptiveCommand implements Command {
    @Override
    public void execute() {
        System.out.println("adaptive ....");
    }
}
