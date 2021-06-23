/*
 * Copyright 2021 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tikv.common.replica;

import java.util.ArrayList;
import java.util.List;
import org.tikv.kvproto.Metapb;

public class LeaderReplicaSelector implements ReplicaSelector {
  @Override
  public List<Metapb.Peer> select(
      Metapb.Peer leader, List<Metapb.Peer> followers, List<Metapb.Peer> learners) {
    List<Metapb.Peer> list = new ArrayList<>();
    list.add(leader);
    return list;
  }
}
