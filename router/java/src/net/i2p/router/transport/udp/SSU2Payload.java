package net.i2p.router.transport.udp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.Log;

/**
 *
 *  SSU2 Payload generation and parsing
 *
 *  @since 0.9.54
 */
class SSU2Payload {

    public static final int BLOCK_HEADER_SIZE = 3;

    private static final int BLOCK_DATETIME = 0;
    private static final int BLOCK_OPTIONS = 1;
    private static final int BLOCK_ROUTERINFO = 2;
    private static final int BLOCK_I2NP = 3;
    private static final int BLOCK_FIRSTFRAG = 4;
    private static final int BLOCK_FOLLOWONFRAG = 5;
    public static final int BLOCK_TERMINATION = 6;
    private static final int BLOCK_RELAYREQ = 7;
    private static final int BLOCK_RELAYRESP = 8;
    private static final int BLOCK_RELAYINTRO = 9;
    private static final int BLOCK_PEERTEST = 10;
    private static final int BLOCK_NEXTNONCE = 11;
    private static final int BLOCK_ACK = 12;
    private static final int BLOCK_ADDRESS = 13;
    private static final int BLOCK_RELAYTAGREQ = 15;
    private static final int BLOCK_RELAYTAG = 16;
    private static final int BLOCK_NEWTOKEN = 17;
    private static final int BLOCK_PATHCHALLENGE = 18;
    private static final int BLOCK_PATHRESP = 19;
    private static final int BLOCK_CONGESTION = 21;
    private static final int BLOCK_PADDING = 254;

    /**
     *  For all callbacks, recommend throwing exceptions only from the handshake.
     *  Exceptions will get thrown out of processPayload() and prevent
     *  processing of succeeding blocks.
     */
    public interface PayloadCallback {
        public void gotDateTime(long time) throws DataFormatException;

        public void gotI2NP(I2NPMessage msg) throws I2NPMessageException;

        /**
         *  Data must be copied out in this method.
         *  Data starts at the 9 byte header for fragment 0.
         *
         *  @param off offset in data
         *  @param len length of data to copy
         */
        public void gotFragment(byte[] data, int off, int len, long messageID, int frag, boolean isLast) throws DataFormatException;

        /**
         *  @param ranges null if none
         */
        public void gotACK(long ackThru, int acks, byte[] ranges);

        /**
         *  @param isHandshake true only for message 3 part 2
         */
        public void gotOptions(byte[] options, boolean isHandshake) throws DataFormatException;

        /**
         *  @param ri will already be validated
         *  @param isHandshake true only for message 3 part 2
         */
        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException;

        /**
         *  @param data is first gzipped and then fragmented
         *  @param isHandshake true only for message 3 part 2
         */
        public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags);

        public void gotAddress(byte[] ip, int port);

        public void gotRelayTagRequest();

        public void gotRelayTag(long tag);

        /**
         *  @param data excludes flag, includes signature
         */
        public void gotRelayRequest(byte[] data);

        /**
         *  @param status 0 = accept, 1-255 = reject
         *  @param data excludes flag, includes signature
         */
        public void gotRelayResponse(int status, byte[] data);

        /**
         *  @param data excludes flag, includes signature
         */
        public void gotRelayIntro(Hash aliceHash, byte[] data);

        /**
         *  @param msg 1-7
         *  @param status 0 = accept, 1-255 = reject
         *  @param h Alice or Charlie hash for msg 2 and 4, null for msg 1, 3, 5-7
         *  @param data excludes flag, includes signature
         */
        public void gotPeerTest(int msg, int status, Hash h, byte[] data);

        public void gotToken(long token, long expires);

        /**
         *  @param lastReceived in theory could wrap around to negative, but very unlikely
         */
        public void gotTermination(int reason, long lastReceived);

        /**
         *  @param from null if unknown
         *  @since 0.9.55
         */
        public void gotPathChallenge(RemoteHostId from, byte[] data);

        /**
         *  @param from null if unknown
         *  @since 0.9.55
         */
        public void gotPathResponse(RemoteHostId from, byte[] data);
    }

    /**
     *  Incoming payload. Calls the callback for each received block.
     *
     *  @param isHandshake true for Token Req, Retry, Sess Req, Sess Created; false for Sess Confirmed
     *  @param from for path challenge/response only, may be null
     *  @return number of blocks processed
     *  @throws IOException on major errors
     *  @throws DataFormatException on parsing of individual blocks
     *  @throws I2NPMessageException on parsing of I2NP block
     */
    public static int processPayload(I2PAppContext ctx, PayloadCallback cb,
                                     byte[] payload, int off, int length, boolean isHandshake, RemoteHostId from)
                                     throws IOException, DataFormatException, I2NPMessageException {
        int blocks = 0;
        boolean gotPadding = false;
        boolean gotTermination = false;
        int i = off;
        final int end = off + length;
        while (i < end) {
            int type = payload[i++] & 0xff;
            if (gotPadding)
                throw new IOException("Illegal block after padding: " + type);
            if (gotTermination && type != BLOCK_PADDING)
                throw new IOException("Illegal block after termination: " + type);
            if (isHandshake && blocks == 0 && type != BLOCK_DATETIME)
                throw new IOException("Illegal first block in handshake: " + type);
            int len = (int) DataHelper.fromLong(payload, i, 2);
            i += 2;
            if (i + len > end) {
                throw new IOException("Block " + blocks + " type " + type + " length " + len +
                                      " at offset " + (i - 3 - off) + " runs over frame of size " + length +
                                      '\n' + net.i2p.util.HexDump.dump(payload, off, length));
            }
            switch (type) {
                // don't modify i inside switch

                case BLOCK_DATETIME:
                    if (len != 4)
                        throw new IOException("Bad length for DATETIME: " + len);
                    long time = DataHelper.fromLong(payload, i, 4) * 1000;
                    cb.gotDateTime(time);
                    break;

                case BLOCK_OPTIONS:
                    byte[] options = new byte[len];
                    System.arraycopy(payload, i, options, 0, len);
                    cb.gotOptions(options, isHandshake);
                    break;

                case BLOCK_ROUTERINFO: {
                    int flag = payload[i] & 0xff;
                    boolean flood = (flag & 0x01) != 0;
                    boolean gz = (flag & 0x02) != 0;
                    int frag = payload[i + 1] & 0xff;
                    int fnum = frag >> 4;
                    int ftot = frag & 0x0f;
                    if (ftot == 0)
                        throw new IOException("Bad fragment count for ROUTERINFO: " + ftot);
                    if (fnum == 0 && ftot == 1) {
                        ByteArrayInputStream bais;
                        if (gz) {
                            byte decompressed[] = DataHelper.decompress(payload, i + 2, len - 2);
                            if (decompressed.length > RouterInfo.MAX_UNCOMPRESSED_SIZE)
                                throw new DataFormatException("RI too big: " + decompressed.length);
                            bais = new ByteArrayInputStream(decompressed);
                        } else {
                            if (len - 2 > RouterInfo.MAX_UNCOMPRESSED_SIZE)
                                throw new DataFormatException("RI too big: " + (len - 2));
                            bais = new ByteArrayInputStream(payload, i + 2, len - 2);
                        }
                        if (bais.available() >= 3*1024)
                            flood = false;
                        RouterInfo alice = new RouterInfo();
                        try {
                            alice.readBytes(bais, true);
                        } catch (DataFormatException dfe) {
                            // alternate verify of signature.
                            // if a badly formatted RI was correctly signed, we do a special callback
                            bais.reset();
                            RouterIdentity ident = new RouterIdentity();
                            ident.readBytes(bais);
                            SigningPublicKey pub = ident.getSigningPublicKey();
                            SigType st = pub.getType();
                            if (st == null)
                                throw dfe;
                            bais.reset();
                            byte[] data = new byte[bais.available() - st.getSigLen()];
                            bais.read(data);
                            Signature sig = new Signature(st);
                            sig.readBytes(bais);
                            if (DSAEngine.getInstance().verifySignature(sig, data, pub)) {
                                Log log = ctx.logManager().getLog(SSU2Payload.class);
                                if (log.shouldWarn())
                                    log.warn("Error reading RI", dfe);
                                // partially filled-in RI, -1 is signal to IES2.gotRI()
                                alice = new RouterInfo();
                                alice.setIdentity(ident);
                                alice.setPublished(-1);
                            } else {
                                // bad sig, just throw dfe
                                throw dfe;
                            }
                        }
                        cb.gotRI(alice, isHandshake, flood);
                    } else {
                        byte[] data = new byte[len - 2];
                        System.arraycopy(payload, i + 2, data, 0, len - 2);
                        cb.gotRIFragment(data, isHandshake, flood, gz, fnum, ftot);
                    }
                    break;
                }

                case BLOCK_I2NP:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    I2NPMessage msg = I2NPMessageImpl.fromRawByteArrayNTCP2(ctx, payload, i, len, null);
                    cb.gotI2NP(msg);
                    break;

                case BLOCK_FIRSTFRAG: {
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len <= 9)
                        throw new IOException("Bad length for FIRSTFRAG: " + len);
                    long id = DataHelper.fromLong(payload, i + 1, 4);
                    cb.gotFragment(payload, i, len, id, 0, false);
                    break;
                }

                case BLOCK_FOLLOWONFRAG: {
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len <= 5)
                        throw new IOException("Bad length for FOLLOWON: " + len);
                    int frag = (payload[i] & 0xff) >> 1;
                    if (frag == 0)
                        throw new IOException("0 frag for FOLLOWON");
                    boolean isLast = (payload[i] & 0x01) != 0;
                    long id = DataHelper.fromLong(payload, i + 1, 4);
                    cb.gotFragment(payload, i + 5, len - 5, id, frag, isLast);
                    break;
                }

                case BLOCK_ACK: {
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 5 || (len % 2) != 1)
                        throw new IOException("Bad length for ACK: " + len);
                    long ack = DataHelper.fromLong(payload, i, 4);
                    int acnt = payload[i + 4] & 0xff;
                    int rcnt = len - 5;
                    byte[] ranges;
                    if (rcnt > 0) {
                        ranges = new byte[rcnt];
                        System.arraycopy(payload, i + 5, ranges, 0, rcnt);
                    } else {
                        ranges = null;
                    }
                    cb.gotACK(ack, acnt, ranges);
                    break;
                }

                case BLOCK_ADDRESS:
                    if (len != 6 && len != 18)
                        throw new IOException("Bad length for Address: " + len);
                    int port = (int) DataHelper.fromLong(payload, i, 2);
                    byte[] ip = new byte[len - 2];
                    System.arraycopy(payload, i + 2, ip, 0, len - 2);
                    cb.gotAddress(ip, port);
                    break;

                case BLOCK_RELAYTAGREQ:
                    cb.gotRelayTagRequest();
                    break;

                case BLOCK_RELAYTAG:
                    if (len < 4)
                        throw new IOException("Bad length for RELAYTAG: " + len);
                    long tag = DataHelper.fromLong(payload, i, 4);
                    cb.gotRelayTag(tag);
                    break;

                case BLOCK_RELAYREQ: {
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 61) // 21 byte data w/ IPv4 + 40 byte DSA sig
                        throw new IOException("Bad length for RELAYREQ: " + len);
                    byte[] data = new byte[len - 1]; // skip flag
                    System.arraycopy(payload, i + 1, data, 0, len - 1);
                    cb.gotRelayRequest(data);
                    break;
                }

                case BLOCK_RELAYRESP: {
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 52) // 12 byte data w/o IP or token + 40 byte DSA sig
                        throw new IOException("Bad length for RELAYRESP: " + len);
                    int resp = payload[i + 1] & 0xff; // skip flag
                    byte[] data = new byte[len - 2];
                    System.arraycopy(payload, i + 2, data, 0, len - 2);
                    cb.gotRelayResponse(resp, data);
                    break;
                }

                case BLOCK_RELAYINTRO: {
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 93) // 32 byte hash + 21 byte data w/ IPv4 + 40 byte DSA sig
                        throw new IOException("Bad length for RELAYINTRO: " + len);
                    Hash h = Hash.create(payload, i + 1); // skip flag
                    byte[] data = new byte[len - (1 + Hash.HASH_LENGTH)]; // skip flag
                    System.arraycopy(payload, i + 1 + Hash.HASH_LENGTH, data, 0, data.length);
                    cb.gotRelayIntro(h, data);
                    break;
                }

                case BLOCK_PEERTEST: {
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 19) // 19 byte data w/ IPv4 (hash and sig optional)
                        throw new IOException("Bad length for PEERTEST: " + len);
                    int mnum = payload[i] & 0xff;
                    if (mnum == 0 || mnum > 7)
                        throw new DataFormatException("Bad PEERTEST number: " + mnum);
                    int resp = payload[i + 1] & 0xff;
                    int o = i + 3; // skip flag
                    int datalen;
                    Hash h;
                    if (mnum == 2 || mnum == 4) {
                        h = Hash.create(payload, o);
                        datalen = len - (3 + Hash.HASH_LENGTH);
                        o += Hash.HASH_LENGTH;
                    } else {
                        datalen = len - 3;
                        h = null;
                    }
                    byte[] data = new byte[datalen];
                    System.arraycopy(payload, o, data, 0, datalen);
                    cb.gotPeerTest(mnum, resp, h, data);
                    break;
                }

                case BLOCK_NEWTOKEN:
                    if (len < 12)
                        throw new IOException("Bad length for NEWTOKEN: " + len);
                    long exp = DataHelper.fromLong(payload, i, 4) * 1000;
                    long token = DataHelper.fromLong8(payload, i + 4);
                    cb.gotToken(token, exp);
                    break;

                case BLOCK_TERMINATION:
                    if (len < 9)
                        throw new IOException("Bad length for TERMINATION: " + len);
                    long last = DataHelper.fromLong8(payload, i);
                    int rsn = payload[i + 8] & 0xff;
                    cb.gotTermination(rsn, last);
                    gotTermination = true;
                    break;

                case BLOCK_PATHCHALLENGE:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    byte[] cdata = new byte[len];
                    System.arraycopy(payload, i, cdata, 0, len);
                    cb.gotPathChallenge(from, cdata);
                    break;

                case BLOCK_PATHRESP:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    byte[] rdata = new byte[len];
                    System.arraycopy(payload, i, rdata, 0, len);
                    cb.gotPathResponse(from, rdata);
                    break;

                case BLOCK_PADDING:
                    gotPadding = true;
                    break;

                default:
                    Log log = ctx.logManager().getLog(SSU2Payload.class);
                    if (log.shouldWarn())
                        log.warn("Got UNKNOWN block, type: " + type + " len: " + len + " on " + cb);
                    break;

            }
            i += len;
            blocks++;
        }
        if (isHandshake && blocks == 0)
            throw new IOException("No blocks in handshake");
        return blocks;
    }

    /**
     *  @param payload writes to it starting at off
     *  @return the new offset
     */
    public static int writePayload(byte[] payload, int off, List<Block> blocks) {
        for (Block block : blocks) {
            off = block.write(payload, off);
        }
        return off;
    }

    /**
     *  Base class for blocks to be transmitted.
     *  Not used for receive; we use callbacks instead.
     */
    public static abstract class Block {
        private final int type;

        public Block(int ttype) {
            type = ttype;
        }

        /**
         *  @since 0.9.55
         */
        public int getType() { return type; }

        /** @return new offset */
        public int write(byte[] tgt, int off) {
            tgt[off++] = (byte) type;
            // we do it this way so we don't call getDataLength(),
            // which may be inefficient
            // off is where the length goes
            int rv = writeData(tgt, off + 2);
            DataHelper.toLong(tgt, off, 2, rv - (off + 2));
            return rv;
        }

        /**
         *  @return the size of the block, including the 3 byte header (type and size)
         */
        public int getTotalLength() {
            return BLOCK_HEADER_SIZE + getDataLength();
        }

        /**
         *  @return the size of the block, NOT including the 3 byte header (type and size)
         */
        public abstract int getDataLength();

        /** @return new offset */
        public abstract int writeData(byte[] tgt, int off);

        @Override
        public String toString() {
            return "Payload block type " + type + " length " + getDataLength();
        }
    }

    public static class RIBlock extends Block {
        private final byte[] data;
        private final int doff, dlen;
        private final boolean f, gz;
        private final int fr, frt;

        /**
         *  Whole thing
         */
        public RIBlock(byte[] ridata, boolean flood, boolean gzipped) {
            this(ridata, 0, ridata.length, flood, gzipped, 0, 1);
        }

        /**
         *  Fragment
         */
        public RIBlock(byte[] ridata, int off, int len, boolean flood, boolean gzipped, int frag, int total) {
            super(BLOCK_ROUTERINFO);
            data = ridata;
            doff = off;
            dlen = len;
            f = flood;
            gz = gzipped;
            fr = frag;
            frt = total;
        }

        public int getDataLength() {
            return 2 + data.length;
        }

        public int writeData(byte[] tgt, int off) {
            byte b = (byte) (f ? 1 : 0);
            if (gz)
                b |= 0x02;
            tgt[off++] = b;    // flag
            b = (byte) ((fr << 4) | frt);
            tgt[off++] = b;    // frag
            System.arraycopy(data, doff, tgt, off, dlen);
            return off + dlen;
        }
    }

    public static class I2NPBlock extends Block {
        private final OutboundMessageState m;

        public I2NPBlock(OutboundMessageState msg) {
            super(BLOCK_I2NP);
            m = msg;
        }

        public int getDataLength() {
            // 9 byte header vs. 16
            return m.getMessageSize();
        }

        public int writeData(byte[] tgt, int off) {
            // fixme NTCP2 flavor
            return off + m.writeFragment(tgt, off, 0);
        }
    }

    /**
     *  Same format as I2NPBlock
     */
    public static class FirstFragBlock extends Block {
        private final OutboundMessageState m;

        public FirstFragBlock(OutboundMessageState msg) {
            super(BLOCK_FIRSTFRAG);
            m = msg;
        }

        public int getDataLength() {
            // 9 byte header vs. 5
            return m.fragmentSize(0); // + 4;
        }

        public int writeData(byte[] tgt, int off) {
            // fixme NTCP2 flavor
            return off + m.writeFragment(tgt, off, 0);
        }
    }

    /**
     *
     */
    public static class FollowFragBlock extends Block {
        private final OutboundMessageState m;
        private final int f;

        public FollowFragBlock(OutboundMessageState msg, int frag) {
            super(BLOCK_FOLLOWONFRAG);
            if (frag <= 0)
                throw new IllegalArgumentException();
            m = msg;
            f = frag;
        }

        public int getDataLength() {
            return m.fragmentSize(f) + 5;
        }

        public int writeData(byte[] tgt, int off) {
            byte b = (byte) (f << 1);
            if (f == m.getFragmentCount() - 1)
                b |= (byte) 0x01;
            tgt[off++] = b;
            DataHelper.toLong(tgt, off, 4, m.getMessageId());
            off += 4;
            return off + m.writeFragment(tgt, off, f);
        }
    }

    public static class PaddingBlock extends Block {
        private final int sz;
        private final I2PAppContext ctx;

        /** with zero-filled data */
        public PaddingBlock(int size) {
            this(null, size);
        }

        /** with random data */
        public PaddingBlock(I2PAppContext context, int size) {
            super(BLOCK_PADDING);
            sz = size;
            ctx = context;
        }

        public int getDataLength() {
            return sz;
        }

        public int writeData(byte[] tgt, int off) {
            if (ctx != null)
                ctx.random().nextBytes(tgt, off, sz);
            else
                Arrays.fill(tgt, off, off + sz, (byte) 0);
            return off + sz;
        }
    }

    public static class DateTimeBlock extends Block {
        private final long now;

        public DateTimeBlock(I2PAppContext ctx) {
            super(BLOCK_DATETIME);
            now = ctx.clock().now();
        }

        public int getDataLength() {
            return 4;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, (now + 500) / 1000);
            return off + 4;
        }
    }

    public static class OptionsBlock extends Block {
        private final byte[] opts;

        public OptionsBlock(byte[] options) {
            super(BLOCK_OPTIONS);
            opts = options;
        }

        public int getDataLength() {
            return opts.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(opts, 0, tgt, off, opts.length);
            return off + opts.length;
        }
    }

    public static class TerminationBlock extends Block {
        private final byte rsn;
        private final long rcvd;

        public TerminationBlock(int reason, long lastReceived) {
            super(BLOCK_TERMINATION);
            rsn = (byte) reason;
            rcvd = lastReceived;
        }

        public int getDataLength() {
            return 9;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong8(tgt, off, rcvd);
            tgt[off + 8] = rsn;
            return off + 9;
        }
    }

    public static class AckBlock extends Block {
        private final long t;
        private final int a;
        private final byte[] r;
        private final int rc;

        /*
         * @param acnt 255 max
         * @param ranges nack/ack/nack/ack
         * @param rangeCount ranges length / 2
         */
        public AckBlock(long thru, int acnt, byte[] ranges, int rangeCount) {
            super(BLOCK_ACK);
            if (acnt > 255)
                throw new IllegalArgumentException();
            t = thru;
            a = acnt;
            r = ranges;
            rc = rangeCount;
        }

        public int getDataLength() {
            return 5 + (rc * 2);
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, t);
            off += 4;
            tgt[off++] = (byte) a;
            System.arraycopy(r, 0, tgt, off, rc * 2);
            return off + (rc * 2);
        }

        @Override
        public String toString() {
            return SSU2Bitfield.toString(t, a, r, rc);
        }
    }

    public static class AddressBlock extends Block {
        private final byte[] i;
        private final int p;

        public AddressBlock(byte[] ip, int port) {
            super(BLOCK_ADDRESS);
            i = ip;
            p = port;
        }

        public int getDataLength() {
            return 2 + i.length;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 2, p);
            off += 2;
            System.arraycopy(i, 0, tgt, off, i.length);
            return off + i.length;
        }
    }

    public static class RelayTagRequestBlock extends Block {

        public RelayTagRequestBlock() {
            super(BLOCK_RELAYTAGREQ);
        }

        public int getDataLength() {
            return 0;
        }

        public int writeData(byte[] tgt, int off) {
            return off;
        }
    }

    public static class RelayTagBlock extends Block {
        private final long t;

        public RelayTagBlock(long tag) {
            super(BLOCK_RELAYTAG);
            t = tag;
        }

        public int getDataLength() {
            return 4;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, t);
            return off + 4;
        }
    }

    public static class RelayRequestBlock extends Block {
        private final byte[] d;

        public RelayRequestBlock(byte[] data) {
            super(BLOCK_RELAYREQ);
            d = data;
        }

        public int getDataLength() {
            return d.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    public static class RelayResponseBlock extends Block {
        private final byte[] d;

        public RelayResponseBlock(byte[] data) {
            super(BLOCK_RELAYRESP);
            d = data;
        }

        public int getDataLength() {
            return d.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    public static class RelayIntroBlock extends Block {
        private final byte[] d;

        public RelayIntroBlock(byte[] data) {
            super(BLOCK_RELAYINTRO);
            d = data;
        }

        public int getDataLength() {
            return d.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    public static class PeerTestBlock extends Block {
        private final int n;
        private final int c;
        private final Hash h;
        private final byte[] d;

        /**
         *  @param hash may be null
         */
        public PeerTestBlock(int msgNum, int code, Hash hash, byte[] data) {
            super(BLOCK_PEERTEST);
            n = msgNum;
            c = code;
            h = hash;
            d = data;
        }

        public int getDataLength() {
            int rv = 3 + d.length;
            if (h != null)
                rv += Hash.HASH_LENGTH;
            return rv;
        }

        public int writeData(byte[] tgt, int off) {
            tgt[off++] = (byte) n;
            tgt[off++] = (byte) c;
            tgt[off++] = 0;  // flag
            if (h != null) {
                System.arraycopy(h.getData(), 0, tgt, off, Hash.HASH_LENGTH);
                off += Hash.HASH_LENGTH;
            }
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    public static class NewTokenBlock extends Block {
        private final EstablishmentManager.Token tok;

        public NewTokenBlock(EstablishmentManager.Token token) {
            super(BLOCK_NEWTOKEN);
            tok = token;
        }

        public int getDataLength() {
            return 12;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, tok.getExpiration() / 1000);
            off += 4;
            DataHelper.toLong8(tgt, off, tok.getToken());
            return off + 8;
        }
    }

    /**
     *  @since 0.9.55
     */
    public static class PathChallengeBlock extends Block {
        private final byte[] d;

        public PathChallengeBlock(byte[] data) {
            super(BLOCK_PATHCHALLENGE);
            d = data;
        }

        public int getDataLength() {
            return d.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    /**
     *  @since 0.9.55
     */
    public static class PathResponseBlock extends Block {
        private final byte[] d;

        public PathResponseBlock(byte[] data) {
            super(BLOCK_PATHRESP);
            d = data;
        }

        public int getDataLength() {
            return d.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }
}
