/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.algorithm.tree;

import java.lang.ref.WeakReference;

/**
 * @author kongweixiang
 * @date 2021/2/1
 * @since 1.0.0
 */
public class Queen {
    public static int num = 0; // 方案数
    public static final int MAXQUEEN = 8; // 皇后数
    public static int[] cols = new int[MAXQUEEN]; // 定义数组，表示MAXQUEEN列棋子中皇后摆放位置
    /*
     * @param n:填第n列的皇后
     */

    public void getCount(int n) {
        boolean[] rows = new boolean[MAXQUEEN];
        for (int m = 0; m < n; m++) {
            rows[cols[m]] = true;
            int d = n - m;
// 正斜方向
            if (cols[m] - d >= 0) {
                rows[cols[m] - d] = true;
            }
// 反斜方向
            if (cols[m] + d <= (MAXQUEEN - 1)) {
                rows[cols[m] + d] = true;
            }
        }
        for (int i = 0; i < MAXQUEEN; i++) {
            if (rows[i]) {
// 不能放
                continue;
            }
            cols[n] = i;
// 下面仍然有合法位置
            if (n < MAXQUEEN - 1) {
                getCount(n + 1);
            } else {
// 找到完整的一套方案
                num++;
                printQueen();
            }
        }
    }

    private void printQueen() {
        System.out.println("第" + num + "种方案");
        for (int i = 0; i < MAXQUEEN; i++) {
            for (int j = 0; j < MAXQUEEN; j++) {
                if (i == cols[j]) {
                    System.out.print("0 ");
                } else {
                    System.out.print("+ ");
                }
            }
            System.out.println();
        }
    }

    public static void main(String[] args) {
        Queen queen = new Queen();
        queen.getCount(0);

//        WeakReference<Queen> reference = new WeakReference<Queen>(new Queen());
//        queen = null;
//        System.gc();
//        System.out.println(reference.get());
//        System.gc();
//        System.out.println(reference.get());

    }
}
