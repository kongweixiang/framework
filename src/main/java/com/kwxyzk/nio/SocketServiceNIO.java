/*
 * Company 上海来伊份电子商务有限公司。
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author kongweixiang
 * @date 2019/9/26
 * @since 1.0.0
 */
public class SocketServiceNIO {

    public static volatile boolean istart = false;

    public static final int packageSize = 48;

//    public Selector selector;
//
//    {
//        try {
//            selector = Selector.open();
//            handleSelector(selector);
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }


    public void ServerSocketService() throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();

        serverSocketChannel.socket().bind(new InetSocketAddress(9999));
        serverSocketChannel.configureBlocking(false);
        AtomicInteger num = new AtomicInteger(0);
        while(true){
            SocketChannel socketChannel = serverSocketChannel.accept();
            if (socketChannel == null || !serverSocketChannel.isOpen()) {
                continue;
            }
            new Thread(() -> {
                try {
                    handConnect(socketChannel,num.incrementAndGet());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public void ServerSocketClient() throws IOException {

        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.connect(new InetSocketAddress("127.0.0.1", 9999));
        socketChannel.configureBlocking(false);
        System.out.println("panduan："+socketChannel.isConnected()+socketChannel.finishConnect());
        while (socketChannel.finishConnect()) {
            //初始化一个ByteBuffer
            ByteBuffer writeBuf = ByteBuffer.allocate(packageSize);
            Scanner scanner = new Scanner(System.in);
            String nextLine = "hello ServerSocketService";
            String servicePackage;
            while (nextLine != null && !"".equals(nextLine)) {
                if(nextLine.length()>packageSize) {
                    int length = nextLine.length() % packageSize == 0 ? nextLine.length() / packageSize : nextLine.length() / packageSize + 1;
                    for (int i = 0; i < length; i++) {
                        if (i == length - 1) {
                            servicePackage = nextLine.substring(i * packageSize);
                        }else {
                            servicePackage = nextLine.substring(i * packageSize,(i+1)* packageSize);
                        }
                        writeBuf.clear();
                        writeBuf.put(servicePackage.getBytes());
                        writeBuf.flip();
                        while (writeBuf.hasRemaining()) {
                            socketChannel.write(writeBuf);
                        }
                    }
                }else {
                    writeBuf.clear();
                    writeBuf.put(nextLine.getBytes());
                    writeBuf.flip();
                    while (writeBuf.hasRemaining()) {
                        socketChannel.write(writeBuf);
                    }
                }
                System.out.println("请输入：" + socketChannel.isConnected());
                nextLine = scanner.nextLine();
                System.out.println("接受数据:"+nextLine);
            }
        }
    }


    public static void main(String[] args) throws IOException {
        final SocketServiceNIO socketNIO = new SocketServiceNIO();
        Thread service = new Thread(() -> {
                try {
                    socketNIO.ServerSocketService();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        service.start();
        Thread client = new Thread(()-> {
                try {
                    socketNIO.ServerSocketClient();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        });
        client.start();
    }

    public void handleSelector(Selector selector) {
        Set<SelectionKey> selectionKeys = selector.selectedKeys();
        Iterator<SelectionKey> keyIterator = selectionKeys.iterator();
        if (keyIterator.hasNext()) {
            SelectionKey key = keyIterator.next();

            if (key.isAcceptable()) {
                // a connection was accepted by a ServerSocketChannel.
                System.out.println("isAcceptable");
            } else if (key.isConnectable()) {
                // a connection was established with a remote server.
                System.out.println("isConnectable");
            } else if (key.isReadable()) {
                // a channel is ready for reading
                System.out.println("isReadable");
            } else if (key.isWritable()) {
                // a channel is ready for writing
                System.out.println("isWritable");
            }
            keyIterator.remove();
        }
    }


    public void  handConnect(SocketChannel socketChannel, int i) throws IOException {
        System.out.println("线程"+i);
        if(socketChannel != null){
            //初始化一个ByteBuffer
            ByteBuffer buf = ByteBuffer.allocate(48);
            //do something with socketChannel...
            if(socketChannel.isConnected()){
                //读取Channel 到Buffer
                int bytesRead = socketChannel.read(buf);
                while (bytesRead != -1) {

                    System.out.println("线程"+i+"Read " + bytesRead);
                    //使 buffer 准备开始读取, 即使Buffer Mode 转化到到 reading mode
                    buf.flip();

                    while(buf.hasRemaining()){
                        System.out.print((char) buf.get()); //读取一个字节
                    }
                    if (bytesRead != 48) {
                        System.out.println();
                    }

                    buf.clear(); //buffer 预备写入(Channel -> Buffer)
                    bytesRead =socketChannel.read(buf);
                }
                System.out.println("------------------------");
            }
        }
    }
}
