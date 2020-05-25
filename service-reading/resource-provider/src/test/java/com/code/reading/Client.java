package com.code.reading;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Client
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/05/19
 * <p>
 * desc：
 */
public class Client {

    public static void main(String[] args) {

        Map<String,String> map = new HashMap<>();

        synchronized (map.get("xxx")){
            System.out.println("...");
        }



        ExecutorService executorService = Executors.newFixedThreadPool(100);
        while (true) {
            List<Integer> list = Arrays.asList(1, 2, 3, 4, 5);
            List<Integer> result = new ArrayList<>(list.size());
            List<Future<?>> futures = new ArrayList<>();
            list.forEach(integer -> {

                Future<?> submit = executorService.submit(() -> {
                    System.out.println(Thread.currentThread().getName());
                    if (integer != null) {
                        result.add(integer);
                    }
                });
                futures.add(submit);

            });

            //  executorService.shutdown();

            futures.forEach(future -> {
                while (!future.isDone()) {
                }
            });
            System.out.println(result);
        }



    }
}
