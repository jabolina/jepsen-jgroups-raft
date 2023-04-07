package org.jgroups.raft.server;

import java.io.DataInput;
import java.util.concurrent.TimeUnit;

import org.jgroups.JChannel;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.raft.blocks.ReplicatedStateMachine;
import org.jgroups.raft.data.Request;
import org.jgroups.raft.data.Response;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.UUID;
import org.jgroups.util.Util;

import static org.jgroups.raft.server.Server.extractCause;

public class ReplicatedMap<K, V> extends ReplicatedStateMachine<K, V> implements TestStateMachine {

  protected final Log log = LogFactory.getLog(getClass());
  public static final byte GET = 3;
  public static final byte CAS = 4;

  public ReplicatedMap(JChannel ch) {
    super(ch);
  }

  @Override
  public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) throws Exception {
    ByteArrayDataInputStream in = new ByteArrayDataInputStream(data, offset, length);
    byte command = in.readByte();

    switch (command) {
      case PUT -> {
        K key = Util.objectFromStream(in);
        V val = Util.objectFromStream(in);
        V old;
        synchronized (map) {
          old = map.put(key, val);
        }

        return old == null ? null
            : serialize_response ? Util.objectToByteBuffer(old) : null;
      }
      case GET -> {
        K key = Util.objectFromStream(in);
        synchronized (map) {
          return Util.objectToByteBuffer(map.get(key));
        }
      }
      case CAS -> {
        K key = Util.objectFromStream(in);
        V from = Util.objectFromStream(in);
        V to = Util.objectFromStream(in);
        synchronized (map) {
          V res = map.compute(key, (ignore, curr) -> {
            if (curr == null) {
              // We do not want to create a new entry.
              // We return false for this case.
              return null;
            }

            return curr.equals(from)
                ? to
                : curr;
          });
          return Util.objectToByteBuffer(res == to && to != null);
        }
      }
      default -> {
        log.error("Unknown command: (%d:%d) %d", offset, length, command);
        throw new IllegalStateException(String.format("Unknown command: %d", command));
      }
    }
  }

  public Response receive(DataInput in) throws Exception {
    int ordinal = in.readByte();
    K key = Util.objectFromStream(in);

    return switch (Server.Command.values()[ordinal]) {
      case PUT -> {
        Request request = Util.objectFromStream(in);
        log.info("PUT: %s --> %s", key, request);
        put(key, cast(request.getValue()));
        yield new Response(request.getUuid(), (String) null);
      }
      case GET -> {
        log.info("GET: " + key);
        UUID uuid = Util.objectFromStream(in);
        boolean quorum = in.readBoolean();
        yield quorum ? new Response(uuid, quorumGet(key)) : new Response(uuid, get(key));
      }
      case CAS -> {
        V from = Util.objectFromStream(in);
        V to = Util.objectFromStream(in);
        UUID uuid = Util.objectFromStream(in);
        try {
          boolean cas = compareAndSet(key, from, to);
          log.info("CAS: %s (%s) -> (%s)? %s", key, from, to, cas);
          yield new Response(uuid, String.valueOf(cas));
        } catch (Exception e) {
          log.error("CAS failed: %s", key, e);
          yield new Response(uuid, extractCause(e));
        }
      }
    };
  }

  private <O> O cast(Object o) {
    return (O) o;
  }

  public V quorumGet(K key) throws Exception {
    return invoke(GET, key, null, false);
  }

  public boolean compareAndSet(K key, V from, V to) throws Exception {
    ByteArrayDataOutputStream out = new ByteArrayDataOutputStream(256);
    out.writeByte(CAS);
    Util.objectToStream(key, out);
    Util.objectToStream(from, out);
    Util.objectToStream(to, out);

    byte[] buf = out.buffer();
    byte[] rsp = raft.set(buf, 0, out.position(), repl_timeout, TimeUnit.MILLISECONDS);
    return Util.objectFromByteBuffer(rsp);
  }
}
