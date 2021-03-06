/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.management.internal.cli.functions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.EvictionAction;
import org.apache.geode.cache.EvictionAlgorithm;
import org.apache.geode.cache.EvictionAttributes;
import org.apache.geode.test.junit.categories.UnitTest;

@Category(UnitTest.class)
public class RegionFunctionArgsTest {

  private RegionFunctionArgs args;
  private RegionFunctionArgs.PartitionArgs partitionArgs;

  @Before
  public void before() {
    args = new RegionFunctionArgs();
    partitionArgs = new RegionFunctionArgs.PartitionArgs();
  }

  @Test
  public void defaultRegionFunctionArgs() throws Exception {
    assertThat(args.isDiskSynchronous()).isNull();
    assertThat(args.isCloningEnabled()).isNull();
    assertThat(args.isConcurrencyChecksEnabled()).isNull();
    assertThat(args.getConcurrencyLevel()).isNull();
    assertThat(args.getPartitionArgs()).isNotNull();
    assertThat(args.getPartitionArgs().hasPartitionAttributes()).isFalse();
    assertThat(args.getEvictionAttributes()).isNull();
  }

  @Test
  public void defaultPartitionArgs() throws Exception {
    assertThat(partitionArgs.hasPartitionAttributes()).isFalse();

    partitionArgs.setPartitionResolver(null);
    assertThat(partitionArgs.hasPartitionAttributes()).isFalse();

    partitionArgs.setPrTotalNumBuckets(10);
    assertThat(partitionArgs.getPrTotalNumBuckets()).isEqualTo(10);
    assertThat(partitionArgs.hasPartitionAttributes()).isTrue();
  }

  @Test
  public void evictionAttributes() throws Exception {
    args.setEvictionAttributes(null, 0, 0, null);
    assertThat(args.getEvictionAttributes()).isNull();

    args.setEvictionAttributes("local-destroy", null, null, null);
    EvictionAttributes attributes = args.getEvictionAttributes();
    assertThat(attributes.getAlgorithm()).isEqualTo(EvictionAlgorithm.LRU_HEAP);
    assertThat(attributes.getAction()).isEqualTo(EvictionAction.LOCAL_DESTROY);
    assertThat(attributes.getMaximum()).isEqualTo(0);

    args.setEvictionAttributes("overflow-to-disk", 1000, null, null);
    EvictionAttributes attributes1 = args.getEvictionAttributes();
    assertThat(attributes1.getAlgorithm()).isEqualTo(EvictionAlgorithm.LRU_MEMORY);
    assertThat(attributes1.getAction()).isEqualTo(EvictionAction.OVERFLOW_TO_DISK);
    assertThat(attributes1.getMaximum()).isEqualTo(1000);

    args.setEvictionAttributes("local-destroy", null, 1000, null);
    EvictionAttributes attributes2 = args.getEvictionAttributes();
    assertThat(attributes2.getAlgorithm()).isEqualTo(EvictionAlgorithm.LRU_ENTRY);
    assertThat(attributes2.getAction()).isEqualTo(EvictionAction.LOCAL_DESTROY);
    assertThat(attributes2.getMaximum()).isEqualTo(1000);
  }

  @Test
  public void evictionAttributesWithNullAction() throws Exception {
    args.setEvictionAttributes(null, null, 1000, null);
    EvictionAttributes attributes3 = args.getEvictionAttributes();
    assertThat(attributes3).isNull();
  }
}
