/*
 * Copyright (C) 2010-2012  The Async HBase Authors.  All rights reserved.
 * This file is part of Async HBase.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the StumbleUpon nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.hbase.async;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import org.jboss.netty.buffer.ChannelBuffer;

final class BatchGet extends HBaseRpc {
  private static final byte[] EMPTY_BYTES = new byte[] { 0 };
  private static final byte[] MULTI = new byte[] {
    'm', 'u', 'l', 't', 'i'
  };

  static class ActionEntry {
    GetRequest rpc;
    int order;
    ActionEntry(GetRequest rpc, int order) {
      this.rpc = rpc;
      this.order = order;
    }
  }

  private final ArrayList<ActionEntry> batch = new ArrayList<ActionEntry>();

  BatchGet() {
    super(MULTI);
  }

  void add(final GetRequest rpc) {
    batch.add(new ActionEntry(rpc, batch.size()));
  }

  void add(final ActionEntry rpcEntry) {
    batch.add(rpcEntry);
  }

  private int predictSerializedSize(final byte server_version) {
    int size = 0;

    size += 4;  // int:  Number of parameters.
    size += 1;  // byte: Type of the 1st parameter.
    size += 1;  // byte: Type again (see HBASE-2877).
    size += 4;  // int:  How many regions do we want to affect?

    byte[] prev_region = EMPTY_BYTES;
    for (ActionEntry entry : batch) {
      GetRequest req = entry.rpc;
      byte[] region_name = req.getRegion().name();
      boolean new_region = !Bytes.equals(prev_region, region_name);
      if (new_region) {
        size += 3; // vint: region name length (3 bytes => max length = 32768).
        size += region_name.length;  // The region name.
        size += 4;  // int:  How many RPCs for this region.
        prev_region = req.getRegion().name();
      }

      // action
      size += 1;  // byte: Type code for 'Action'.
      size += 1;  // byte: Type code again.
      size += 1;  // byte: Type code for 'Row'
      size += 1;  // byte: Type code for 'Get'

      size += req.predictPayloadSize(server_version);

      size += 4;  // int: Index of the action.
      size += 3;  // 3bytes: action result 'null'
    }
    return size;
  }

  ChannelBuffer serialize(final byte server_version) {
    Collections.sort(batch, REGION_CMP);
    
    ChannelBuffer buf = newBuffer(server_version,
                                  predictSerializedSize(server_version));

    buf.writeInt(1);  // Only 1 parameter for multi (which is MultiAction.)
    buf.writeByte(66);  // Type code for 'MultiAction'.
    buf.writeByte(66);  // Type code again.

    int nregion_index = buf.writerIndex();
    buf.writeInt(0);  // total number of regions. Will fill it at the end.

    byte[] prev_region = EMPTY_BYTES;
    int nregions = 0;
    int ngets_index = 0;
    int ngets = 0;
    int i = 0;
    for (ActionEntry entry : batch) {
      GetRequest req = entry.rpc;
      byte[] region_name = req.getRegion().name();
      boolean new_region = !Bytes.equals(prev_region, region_name);
      if (new_region) {
        // new region starts. Let's first fill the num of rpcs for the previous region.
        if (ngets_index > 0) {
          buf.setInt(ngets_index, ngets);
          ngets = 0;
        }
        writeByteArray(buf, region_name);  // region name;
        ngets_index = buf.writerIndex();
        buf.writeInt(0);  // will be filled when we pass this region.
        prev_region = region_name;
        nregions++;
      }

      buf.writeByte(65);  // Code for an `Action' object.
      buf.writeByte(65);  // Code again (see HBASE-2877).

      // Inside the action, serialize a `Get' object.
      buf.writeByte(64);  // Type code for a `Row' object.
      buf.writeByte(32);  // Type code for 'Get'.

      req.serializePayloadInto(server_version, buf);  // GET

      buf.writeInt(i);  // index of the get. we use its index in batch here.
      writeHBaseNull(buf);  // 'Action' ends with null result in our case.
      ngets++;
      i++;
    }
    if (ngets_index > 0) {
      buf.setInt(ngets_index, ngets);
    }
    buf.setInt(nregion_index, nregions);
    return buf;
  }

  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("BatchGet(batch=[");
    final int nrpcs = batch.size();
    int i;
    for (i = 0; i < nrpcs; i++) {
      if (buf.length() >= 1024) {
        break;
      }
      buf.append(batch.get(i)).append(", ");
    }
    if (i < nrpcs) {
      if (i == nrpcs - 1) {
        buf.append("... 1 RPC not shown])");
      } else {
        buf.append("... ").append(nrpcs - 1 - i)
          .append(" RPCs not shown ..., ")
          .append(batch.get(nrpcs - 1))
          .append("])");
      }
    } else {
      buf.setLength(buf.length() - 2);  // Remove the last ", "
      buf.append("])");
    }
    return buf.toString();
  }

  static final RegionComparator REGION_CMP = new RegionComparator();

  /** Sorts {@link BatchableRpc}s appropriately for the `multi' RPC.  */
  private static final class RegionComparator
    implements Comparator<ActionEntry> {

    private RegionComparator() {  // Can't instantiate outside of this class.
    }

    @Override
    /**
     * Compares two RPCs.
     */
    public int compare(final ActionEntry a, final ActionEntry b) {
      return Bytes.memcmp(a.rpc.getRegion().name(), b.rpc.getRegion().name());
    }
  }

  static class ActionResp {
    Object result;
    int order;

    ActionResp(Object result, int order) {
      this.result = result;
      this.order = order;
    }
  }

  /**
   * De-serializes a {@code MultiResponse}.
   * This is only used when talking to HBase 0.92 and above.
   */
  ActionResp[] decodeMultiResponse(final ChannelBuffer buf) {
    byte code = buf.readByte();  // this should be code of MultiResponse 67.

    final int nregions = buf.readInt();  // num regions
    HBaseRpc.checkNonEmptyArrayLength(buf, nregions);
    final ActionResp[] resps = new ActionResp[batch.size()];
    int n = 0;
    for (int i = 0; i < nregions; i++) {
      final byte[] region_name = HBaseRpc.readByteArray(buf);  // region name
      final int nrpcs = buf.readInt();  // rpcs in this region.
      HBaseRpc.checkNonEmptyArrayLength(buf, nrpcs);
      for (int j = 0; j < nrpcs; j++) {
        final int nrpcs_index = buf.readInt();  // index of this rpc as passed to the server.
        final ActionEntry entry = batch.get(nrpcs_index);
        boolean error = buf.readByte() != 0x00;  // success code.
        Object resp;  // Response for the current region.
        if (error) {
          final HBaseException e = RegionClient.deserializeException(buf, entry.rpc);
          resp = e;
        } else {
          resp = RegionClient.deserializeObject(buf, this);
          if (resp == null) {
            // This is NSRE
            resp = new NotServingRegionException(
                "Not serving region: " + Bytes.pretty(region_name), entry.rpc);
          }
        }
        resps[n++] = new ActionResp(resp, entry.order);
      }
    }
    return resps;
  }
}
