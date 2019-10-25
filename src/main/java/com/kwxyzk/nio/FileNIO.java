/*
 * Company 上海来伊份电子商务有限公司。
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.nio;

import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 文件NIO
 * @author kongweixiang
 * @date 2019/9/19
 * @since 1.0.0
 */
public class FileNIO {
    public void fileChannel() throws IOException {
        RandomAccessFile aFile = new RandomAccessFile("D:\\test.txt", "rw");
        //获取一个Channel
        FileChannel inChannel = aFile.getChannel();

        //初始化一个ByteBuffer
        ByteBuffer buf = ByteBuffer.allocate(48);

        //读取Channel 到Buffer
        int bytesRead = inChannel.read(buf);
        while (bytesRead != -1) {

            System.out.println("Read " + bytesRead);
            //使 buffer 准备开始读取, 即使Buffer Mode 转化到到 reading mode
            buf.rewind();

            while(buf.hasRemaining()){
                System.out.print((char) buf.get()); //读取一个字节
            }

            buf.clear(); //buffer 预备写入(Channel -> Buffer)
            bytesRead = inChannel.read(buf);
        }
        aFile.close();
    }



    public static void main(String[] args) throws IOException {
        FileNIO fileNIO = new FileNIO();
        fileNIO.fileChannel();
    }
}
