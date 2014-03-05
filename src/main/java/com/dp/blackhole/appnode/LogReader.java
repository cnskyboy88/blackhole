package com.dp.blackhole.appnode;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.dp.blackhole.collectornode.persistent.ByteBufferMessageSet;
import com.dp.blackhole.collectornode.persistent.Message;
import com.dp.blackhole.collectornode.persistent.protocol.ProduceRequest;
import com.dp.blackhole.collectornode.persistent.protocol.RegisterRequest;
import com.dp.blackhole.collectornode.persistent.protocol.RotateRequest;
import com.dp.blackhole.common.ParamsKey;
import com.dp.blackhole.common.Util;
import com.dp.blackhole.conf.ConfigKeeper;
import com.dp.blackhole.network.TransferWrap;

public class LogReader implements Runnable{
    private static final Log LOG = LogFactory.getLog(LogReader.class);
    private static final Charset DEFAULT_CHARSET = Charset.defaultCharset();
    private AppLog appLog;
    private Appnode node;
    private String localhost;
    private String broker;
    private int brokerPort;
    private int bufSize;
    private Socket socket;
    EventWriter eventWriter;
    
    public LogReader(Appnode node, String localhost, String broker, int port, AppLog appLog) {
        this.node = node;
        this.localhost = localhost;
        this.broker = broker;
        this.brokerPort = port;
        this.appLog = appLog;
        this.bufSize = ConfigKeeper.configMap.get(appLog.getAppName()).getInteger(ParamsKey.Appconf.BUFFER_SIZE, 4096);
    }

    public void stop() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            LOG.warn("Warnning, clean fail:", e);
        }
        LOG.debug("Perpare to unregister LogReader: " + this.toString() + ", with " + appLog.getTailFile());
        node.getListener().unregisterLogReader(appLog.getTailFile());
    }

    @Override
    public void run() {
        try {
            LOG.info("Log reader for " + appLog + " running...");
            
            File tailFile = new File(appLog.getTailFile());
            this.eventWriter = new EventWriter(tailFile, bufSize);
            
            if (!node.getListener().registerLogReader(appLog.getTailFile(), this)) {
                throw new IOException("Failed to register a log reader for " + appLog.getAppName() 
                        + " with " + appLog.getTailFile() + ", thread will not run.");
            }
        } catch (UnknownHostException e) {
            LOG.error("Socket fail!", e);
            node.reportFailure(appLog.getAppName(), localhost, Util.getTS());
        } catch (IOException e) {
            LOG.error("Oops, got an exception", e);
            node.reportFailure(appLog.getAppName(), localhost, Util.getTS());
        } catch (RuntimeException e) {
            LOG.error("Oops, got an RuntimException:" , e);
            node.reportFailure(appLog.getAppName(), localhost, Util.getTS());
        }
    }

    class EventWriter {
        private final Charset cset;
        private final File file;
        private final byte inbuf[];
        private SocketChannel channel;
        private RandomAccessFile reader;
        
        private ByteBuffer messageBuffer;
        private int messageNum;
        
        public EventWriter(final File file, final int bufSize) throws IOException {
            this(file, bufSize, DEFAULT_CHARSET);
        }
        public EventWriter(final File file, final int bufSize, Charset cset) throws IOException {
            this.file = file;
            this.inbuf = new byte[bufSize];
            this.cset = cset;
            
            channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(broker, brokerPort));
            
            doStreamReg();
            
            this.reader = new RandomAccessFile(file, "r");
            messageBuffer = ByteBuffer.allocate(512 * 1024);
        }
        
        private void doStreamReg() throws IOException {
            RegisterRequest request = new RegisterRequest(appLog.getAppName(), localhost, appLog.getRollPeriod(), broker);
            TransferWrap wrap = new TransferWrap(request);
            wrap.write(channel);
        }
        
        public void processRotate() {
            try {
                final RandomAccessFile save = reader;
                reader = new RandomAccessFile(file, "r");
                // At this point, we're sure that the old file is rotated
                // Finish scanning the old file and then we'll start with the new one
                readLines(save);
                closeQuietly(save);
                
                RotateRequest request = new RotateRequest(appLog.getAppName(), localhost, appLog.getRollPeriod());
                TransferWrap wrap = new TransferWrap(request);
                wrap.write(channel);
            } catch (IOException e) {
                LOG.error("Oops, got an exception:", e);
                closeQuietly(reader);
                closeChannelQuietly(channel);
                LOG.debug("process rotate failed, stop.");
                stop();
                node.reportFailure(appLog.getAppName(), localhost, Util.getTS());
            }
        }
        
        public void process() {
            try {
                readLines(reader);
            } catch (IOException e) {
                LOG.error("Oops, process read lines fail:", e);
                closeQuietly(reader);
                closeChannelQuietly(channel);
                LOG.debug("process failed, stop.");
                stop();
                node.reportFailure(appLog.getAppName(), localhost, Util.getTS());
            }
        }
        
        private long readLines(RandomAccessFile reader) throws IOException {
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(64);
            long pos = reader.getFilePointer();
            long rePos = pos; // position to re-read
            int num;
            while ((num = reader.read(inbuf)) != -1) {
                for (int i = 0; i < num; i++) {
                    final byte ch = inbuf[i];
                    switch (ch) {
                    case '\n':
                        handleLine(lineBuf.toByteArray());
                        lineBuf.reset();
                        rePos = pos + i + 1;
                        break;
                    default:
                        lineBuf.write(ch);
                    }
                }
                pos = reader.getFilePointer();
            }
            closeQuietly(lineBuf); // not strictly necessary
            reader.seek(rePos); // Ensure we can re-read if necessary
            return rePos;
        }
        
        private void sendMessage() throws IOException {
            messageBuffer.flip();
            ByteBufferMessageSet messages = new ByteBufferMessageSet(messageBuffer.slice());
            ProduceRequest request = new ProduceRequest(appLog.getAppName(), localhost, messages);
            TransferWrap wrap = new TransferWrap(request);
            wrap.write(channel);
            messageBuffer.clear();
            messageNum = 0;
        }
        
        private void handleLine(byte[] line) throws IOException {
            
            Message message = new Message(line); 
            
            if (messageNum >= 30 || message.getSize() > messageBuffer.remaining()) {
                sendMessage();
            }
            
            message.write(messageBuffer);
            messageNum++;
        }
        
        /**
         * Unconditionally close a Closeable.
         * Equivalent to close(), except any exceptions will be ignored.
         * This is typically used in finally blocks.
         */
        private void closeQuietly(Closeable closeable) {
            try {
                if (closeable != null) {
                    closeable.close();
                }
            } catch (IOException ioe) {
                // ignore
            }
        }
        
        private void closeChannelQuietly(SocketChannel channel) {
            try {
                channel.socket().shutdownOutput();
            } catch (IOException e1) {
                e1.printStackTrace();
            }

            try {
                channel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                channel.socket().close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}