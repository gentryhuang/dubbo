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
package org.apache.dubbo.rpc.cluster.router.tag.model;

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.cluster.router.AbstractRouterRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * %YAML1.2
 * ---
 * force: true
 * runtime: false
 * enabled: true
 * priority: 1
 * key: demo-provider
 * tags:
 * - name: tag1
 * addresses: [ip1, ip2]
 * - name: tag2
 * addresses: [ip3, ip4]
 * ...
 */
public class TagRouterRule extends AbstractRouterRule {
    /**
     * Tag 集合
     */
    private List<Tag> tags;
    /**
     * address 到 tag 名称的映射
     */
    private Map<String, List<String>> addressToTagnames = new HashMap<>();
    /**
     * tag 名称到 address 的映射
     */
    private Map<String, List<String>> tagnameToAddresses = new HashMap<>();

    /**
     * 根据 Tag 集合初始化 addressToTagnames 和 tagnameToAddresses
     */
    public void init() {
        if (!isValid()) {
            return;
        }

        tags.stream().filter(tag -> CollectionUtils.isNotEmpty(tag.getAddresses())).forEach(tag -> {
            // tag 名称 到 address 的映射
            tagnameToAddresses.put(tag.getName(), tag.getAddresses());

            // address 到 tag 名称的映射
            tag.getAddresses().forEach(addr -> {
                List<String> tagNames = addressToTagnames.computeIfAbsent(addr, k -> new ArrayList<>());
                tagNames.add(tag.getName());
            });
        });
    }

    /**
     * 获取 Tag 集合中 所有的 address
     *
     * @return
     */
    public List<String> getAddresses() {
        return tags.stream()
                .filter(tag -> CollectionUtils.isNotEmpty(tag.getAddresses()))
                .flatMap(tag -> tag.getAddresses().stream())
                .collect(Collectors.toList());
    }

    /**
     * 获取 Tag 集合中所有的 tag 名称
     *
     * @return
     */
    public List<String> getTagNames() {
        return tags.stream().map(Tag::getName).collect(Collectors.toList());
    }

    public Map<String, List<String>> getAddressToTagnames() {
        return addressToTagnames;
    }


    public Map<String, List<String>> getTagnameToAddresses() {
        return tagnameToAddresses;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }
}
