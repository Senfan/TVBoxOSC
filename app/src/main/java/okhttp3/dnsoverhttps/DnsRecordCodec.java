/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package okhttp3.dnsoverhttps;

import java.io.EOFException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import okio.Buffer;
import okio.ByteString;
import okio.Utf8;

/**
 * Trivial Dns Encoder/Decoder, basically ripped from Netty full implementation.
 */
class DnsRecordCodec {
  private static final byte SERVFAIL = 2;
  private static final byte NXDOMAIN = 3;
  public static final int TYPE_A = 0x0001;
  public static final int TYPE_AAAA = 0x001c;
  private static final int TYPE_PTR = 0x000c;
  private static final Charset ASCII = Charset.forName("ASCII");

  private DnsRecordCodec() {
  }

  public static ByteString encodeQuery(String host, int type) {
    Buffer buf = new Buffer();

    buf.writeShort(0); // query id
    buf.writeShort(256); // flags with recursion
    buf.writeShort(1); // question count
    buf.writeShort(0); // answerCount
    buf.writeShort(0); // authorityResourceCount
    buf.writeShort(0); // additional

    Buffer nameBuf = new Buffer();
    final String[] labels = host.split("\\.");
    for (String label : labels) {
      long utf8ByteCount = Utf8.size(label);
      if (utf8ByteCount != label.length()) {
        throw new IllegalArgumentException("non-ascii hostname: " + host);
      }
      if (utf8ByteCount == 0 || utf8ByteCount > 63) {
        throw new IllegalArgumentException("invalid DNS label: " + host);
      }
      nameBuf.writeByte((byte) utf8ByteCount);
      nameBuf.writeUtf8(label);
    }
    nameBuf.writeByte(0); // end
    if (nameBuf.size() > 255) {
      throw new IllegalArgumentException("hostname too long: " + host);
    }

    nameBuf.copyTo(buf, 0, nameBuf.size());
    buf.writeShort(type);
    buf.writeShort(1); // CLASS_IN

    return buf.readByteString();
  }

  public static List<InetAddress> decodeAnswers(String hostname, ByteString byteString)
      throws Exception {
    List<InetAddress> result = new ArrayList<>();

    Buffer buf = new Buffer();
    buf.write(byteString);
    buf.readShort(); // query id

    final int flags = buf.readShort() & 0xffff;
    if (flags >> 15 == 0) {
      throw new IllegalArgumentException("not a response");
    }

    byte responseCode = (byte) (flags & 0xf);

    if (responseCode == NXDOMAIN) {
      throw new UnknownHostException(hostname + ": NXDOMAIN");
    } else if (responseCode == SERVFAIL) {
      throw new UnknownHostException(hostname + ": SERVFAIL");
    }

    final int questionCount = buf.readShort() & 0xffff;
    final int answerCount = buf.readShort() & 0xffff;
    buf.readShort(); // authority record count
    buf.readShort(); // additional record count

    for (int i = 0; i < questionCount; i++) {
      skipName(buf); // name
      buf.readShort(); // type
      buf.readShort(); // class
    }

    for (int i = 0; i < answerCount; i++) {
      skipName(buf); // name

      int type = buf.readShort() & 0xffff;
      buf.readShort(); // class
      final long ttl = buf.readInt() & 0xffffffffL; // ttl
      final int length = buf.readShort() & 0xffff;

      if (type == TYPE_A || type == TYPE_AAAA) {
        if (length != (type == TYPE_A ? 4 : 16)) {
          throw new IllegalArgumentException("invalid DNS address length: " + length);
        }
        byte[] bytes = buf.readByteArray(length);
        result.add(InetAddress.getByAddress(hostname, bytes));
      } else {
        buf.skip(length);
      }
    }

    return result;
  }

  private static void skipName(Buffer in) throws EOFException {
    while (true) {
      int length = in.readByte() & 0xff;
      if (length == 0) return;
      if ((length & 0xc0) == 0xc0) {
        in.skip(1); // compressed suffix, including after an uncompressed label
        return;
      }
      if ((length & 0xc0) != 0) {
        throw new IllegalArgumentException("invalid DNS label length");
      }
      in.skip(length);
    }
  }
}
