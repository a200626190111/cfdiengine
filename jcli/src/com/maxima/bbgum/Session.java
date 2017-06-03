package com.maxima.bbgum;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Deque;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;

class Session extends Thread {

    private Socket socket;
    private Deque<Frame> writeChunks;
    private Object outGoingMutex;
    private Monitor mon;

    public Session(final String serverAddress, final int port, BasicFactory<Byte, EventController> factory) throws IOException {
        this(new Socket(serverAddress, port), factory);
    }

    public Session(Socket socket, BasicFactory<Byte, EventController> factory) {
        this.socket = socket;
        this.writeChunks = new LinkedList<Frame>();
        this.outGoingMutex = new Object();
        this.mon = new Monitor(this, factory);
    }

    @Override
    public void run() {
        for (;;) {
            InputStream is = null;
            try {
                is = this.socket.getInputStream();
                if (this.readHeadHandler(is) < 0) {
                    Logger.getLogger(Session.class.getName()).log(
                            Level.SEVERE, null, "Connection has been closed!!");
                    break;
                }
            } catch (IOException ex) {
                Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, ex);
            } catch (SessionError ex) {
                Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                try {
                    is.close();
                } catch (IOException ex) {
                    Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    public ServerReply pushBuffer(final byte archetype, final byte[] buffer, final boolean block) throws SessionError {
        return this.mon.pushBuffer(archetype, buffer, block);
    }

    public void deliver(Action action) throws SessionError {

        Frame f = null;

        try {
            f = new Frame(action);
        } catch (FrameError ex) {
            Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, ex);
            String msg = "Frame could not be conformed with given action";
            throw new SessionError(msg);
        }

        boolean writeInProgress;

        synchronized (outGoingMutex) {
            writeInProgress = !this.writeChunks.isEmpty();
            this.writeChunks.addLast(f);
        }

        if (!writeInProgress) {
            byte[] data = this.writeChunks.getFirst().getFrame();
            OutputStream os;
            try {
                os = this.socket.getOutputStream();
                os.write(data, 0,
                    Frame.FRAME_HEADER_LENGTH +
                    Frame.ACTION_FLOW_INFO_SEGMENT_LENGTH +
                    action.getBuffer().length);
                os.flush();
                this.release();
            } catch (IOException ex) {
                Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, ex);
                String msg = "Problems when writting socket";
                throw new SessionError(msg);
            }
        }
    }

    private void release() throws IOException {

        boolean isNotEmpty;
        synchronized (outGoingMutex) {
            this.writeChunks.pop();
            isNotEmpty = !this.writeChunks.isEmpty();
        }

        if (isNotEmpty) {
            Frame frame = this.writeChunks.getFirst();
            int lengthActionData = frame.getAction().getBuffer().length;
            byte[] data = frame.getFrame();

            OutputStream os = this.socket.getOutputStream();

            os.write(data, 0,
                Frame.FRAME_HEADER_LENGTH +
                Frame.ACTION_FLOW_INFO_SEGMENT_LENGTH +
                lengthActionData);

            os.flush();
            this.release();
        }
    }

    private int readBodyHandler(InputStream is, int size) throws Exception {
        int rc = 0;

        byte[] receivedBytes = new byte[size];
        int res = is.read(receivedBytes, 0, size);

        if (res < 0) rc = res;
        else {
            Action action = new Action(receivedBytes);
            this.mon.recive(action);
        }

        return rc;
    }

    private int readHeadHandler(InputStream is) throws SessionError {
        int rc = 0;

        byte[] receivedBytes = new byte[Frame.FRAME_HEADER_LENGTH];
        int res = 0;
        try {
            res = is.read(receivedBytes, 0,
                Frame.FRAME_HEADER_LENGTH);
        } catch (IOException ex) {
            String msg = "Problems ocurried when reading"
                    + " frame header from socket";
            Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, msg);
            throw new SessionError(ex.getMessage());
        }

        if (res < 0) rc = res;
        else {
            int size = 0;
            try {
                size = Frame.decodeHeader(receivedBytes);
            } catch (FrameError ex) {
                Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, ex);
            }

            if (size < 0) rc = size;
            else {
                try {
                    res = this.readBodyHandler(is, size);
                } catch (Exception ex) {
                    String msg = "Problems ocurried when reading"
                        + " frame body from socket";
                    Logger.getLogger(Session.class.getName()).log(Level.SEVERE, null, msg);
                    throw new SessionError(ex.getMessage());
                }
                if (res < 0) rc = res;
            }
        }

        return rc;
    }

    public static final byte EVENT_BUFFER_TRANSFER = (byte) 0x28;
}

