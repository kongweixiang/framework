/*
 * Company 上海来伊份电子商务有限公司。
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author kongweixiang
 * @date 2019/9/26
 * @since 1.0.0
 */
public class SocketNIO {


    public static final int PACKAGE_SIZE = 48; //数据传输包大小

    public static final char PACKAGE_END_TAG = '`'; //数据业务分包标识

    public StringBuilder stringBuilder = new StringBuilder(); //用于接受读请求

    ThreadLocal<AtomicInteger> acceptNum = new ThreadLocal<>(); //请求通道个数
    ThreadLocal<AtomicInteger> connectNum = new ThreadLocal<>(); //连接通道个数
    ThreadLocal<AtomicInteger> readNum= new ThreadLocal<>(); //读通道个数
    ThreadLocal<AtomicInteger> writeNum = new ThreadLocal<>();//请求通道个数
    {
        acceptNum.set(new AtomicInteger(0));
        connectNum.set(new AtomicInteger(0));
        readNum.set(new AtomicInteger(0));
        writeNum.set(new AtomicInteger(0));
    }


    public void ServerSocketService(Selector selector) throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();

        serverSocketChannel.socket().bind(new InetSocketAddress(9999));
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, "accept" + acceptNum.get().incrementAndGet());

    }

    public void ServerSocketClient(Selector selector) throws IOException {

        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.connect(new InetSocketAddress("127.0.0.1", 9999));
        socketChannel.configureBlocking(false);
        socketChannel.register(selector,SelectionKey.OP_WRITE,"write" + writeNum.get().incrementAndGet());
    }


    public static void main(String[] args) throws IOException {
        SocketNIO socketNIO = new SocketNIO();
        Selector selector = Selector.open();
        socketNIO.ServerSocketService(selector);
        socketNIO.ServerSocketClient(selector);
        socketNIO.handleSelector(selector);

    }

    public void handleSelector(Selector selector) throws IOException {
        while (true) {
            selector.select(6000);
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectionKeys.iterator();
            Handler handler = new Handler();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if(!key.channel().isOpen()){
                    keyIterator.remove();
                }
                if (key.isAcceptable()) {
                    // a connection was accepted by a ServerSocketChannel.
                    System.out.println("isAcceptable" + key.attachment());
                    try {
                        handler.handleAccept(key);
                    } catch (IOException e) {
                        e.printStackTrace();
                        keyIterator.remove();
                    }
                } else if (key.isConnectable()) {
                    // a connection was established with a remote server.
                    System.out.println("isConnectable" + key.attachment());
                    handler.handleConnect(key);
                } else if (key.isReadable()) {
                    // a channel is ready for reading
                    System.out.println("isReadable" + key.attachment());
                    try {
                        handler.handleRead(key);
                    } catch (IOException e) {
                        e.printStackTrace();
                        keyIterator.remove();
                    }
                } else if (key.isWritable()) {
                    // a channel is ready for writing
                    System.out.println("isWritable" + key.attachment());
                    try {
                        handler.handleWrite(key);
                    } catch (IOException e) {
                        e.printStackTrace();
                        keyIterator.remove();
                    }
                }
            }
        }
    }

   private class Handler{
       private int bufferSize = 1024; //缓冲器容量
       private String localCharset = "UTF-8"; //编码格式

       Handler(){}


       public Handler(int bufferSize) {
           this.bufferSize = bufferSize;
       }

       public void handleAccept(SelectionKey selectionKey) throws IOException {
           ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();
           SocketChannel socketChannel = null;
           try {
               socketChannel = serverSocketChannel.accept();
           } catch (IOException e) {
               e.printStackTrace();
           }
           if (socketChannel == null) {
               return;
           }
           System.out.println("selectionKey is in handleAccept");
           try {
               socketChannel.configureBlocking(false);
           } catch (IOException e) {
               e.printStackTrace();
           }
           try {
               socketChannel.register(selectionKey.selector(), SelectionKey.OP_READ,"read" + readNum.get().incrementAndGet());
           } catch (ClosedChannelException e) {
               e.printStackTrace();
           }
       }

       public void handleConnect(SelectionKey selectionKey){
       }

       public void handleRead(SelectionKey selectionKey) throws IOException {
           SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
           if(socketChannel != null){
               //初始化一个ByteBuffer
               ByteBuffer buf = ByteBuffer.allocate(bufferSize);
               buf.clear(); //buffer 预备写入(Channel -> Buffer)
               //do something with socketChannel...
               if(socketChannel.isConnected()){
                   List<String> packages = new ArrayList<>(); //请求包
                   StringBuilder stringBuilder = new StringBuilder();
                   //读取Channel 到Buffer
                   int bytesRead = socketChannel.read(buf);
                   while (bytesRead != -1 && bytesRead != 0 ) {

                       System.out.println("Read " + bytesRead);
                       //使 buffer 准备开始读取, 即使Buffer Mode 转化到到 reading mode
                       buf.flip();

                       while(buf.hasRemaining()){
                           char c = (char) buf.get();
                           if (c == PACKAGE_END_TAG) {
                               packages.add(stringBuilder.toString());
                               stringBuilder = new StringBuilder();
                           }else {
                               stringBuilder.append(c);
                           }
                       }
                       if (bytesRead != PACKAGE_SIZE) {
                           System.out.println();
                       }

                       buf.clear(); //buffer 预备写入(Channel -> Buffer)
                       if (bytesRead != bufferSize) {
                           break;
                       }
                       bytesRead =socketChannel.read(buf);
                   }
                   packages.stream().forEach(System.out::println);
               }
           }

       }
       public void handleWrite(SelectionKey selectionKey) throws IOException {
           SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
           System.out.println("判断连接情况："+socketChannel.isConnected()+socketChannel.finishConnect());
           if (socketChannel.finishConnect()) {
               //初始化一个ByteBuffer
               ByteBuffer writeBuf = ByteBuffer.allocate(PACKAGE_SIZE);
               Scanner scanner = new Scanner(System.in);
               System.out.println("请输入：" + socketChannel.isConnected());
               String nextLine = scanner.nextLine();
               System.out.println("接受数据:"+nextLine);
               String servicePackage;
               if (nextLine != null && !"".equals(nextLine)) {
                   if(nextLine.length()>PACKAGE_SIZE) {
                       int length = nextLine.length() % PACKAGE_SIZE == 0 ? nextLine.length() / PACKAGE_SIZE : nextLine.length() / PACKAGE_SIZE + 1;
                       for (int i = 0; i < length; i++) {
                           if (i == length - 1) {
                               servicePackage = nextLine.substring(i * PACKAGE_SIZE);
                           }else {
                               servicePackage = nextLine.substring(i * PACKAGE_SIZE,(i+1)* PACKAGE_SIZE);
                           }
                           writeBuf.clear();
                           servicePackage += PACKAGE_END_TAG;
                           writeBuf.put(servicePackage.getBytes());
                           writeBuf.flip();
                           while (writeBuf.hasRemaining()) {
                               socketChannel.write(writeBuf);
                           }
                       }
                   }else {
                       writeBuf.clear();
                       servicePackage = nextLine + PACKAGE_END_TAG;
                       writeBuf.put(servicePackage.getBytes());
                       writeBuf.flip();
                       while (writeBuf.hasRemaining()) {
                           socketChannel.write(writeBuf);
                       }
                   }
//                   scanner.close();
               }
           }
       }

   }
}
