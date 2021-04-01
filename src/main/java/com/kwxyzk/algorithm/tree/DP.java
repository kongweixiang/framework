/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.algorithm.tree;

import org.apache.kafka.common.metrics.stats.Max;

import java.util.Random;

/**
 * @author kongweixiang
 * @date 2021/3/17
 * @since 1.0.0
 */
public class DP {
    private static int MAX = 101;
    private static int[][] D = new int[MAX][MAX];   //存储数字三角形
    private static int n = 5;              //n表示层数
    private static int[][] maxSum = new int[MAX][MAX];   //存储数字上的和


    public static void main(String[] args) {
        DP test = new DP();
        Random random = new Random();
        for (int i = 0; i < n; i++) {
            for (int j=0;j<=i;j++){
                D[i][j] = random.nextInt(10);
                System.out.print(D[i][j]+"  ");
                maxSum[i][j] = -1;
            }
            System.out.println();
        }
        System.out.println(test.getMax());
        System.out.println(test.getMax2());
//        System.out.println(test.minPathSum());
    }

    public static int getMax(){

        
        for (int i = 0; i < n; i++) {
            maxSum[n][i] =D[n][i];
        }
        int i = 0; int j = 0;
        int maxSum = getMaxSum(D,n,i,j);
        return maxSum;
    }
    public static int getMax2(){
        for (int i = 0; i < n; i++) {
            maxSum[n-1][i] =D[n-1][i];
        }
        for (int i = n-2; i >=0; i--) {
            for (int j = 0; j <= i; j++) {
                maxSum[i][j] = Math.max(maxSum[i + 1][j], maxSum[i + 1][j + 1]) + D[i][j];
            }
        }
        return maxSum[0][0];
    }
    public static int getMaxSum(int[][] D,int n,int i,int j){
        if(maxSum[i][j]!=-1)
            return maxSum[i][j];
        if(i == n){
            return D[i][j];
        }
        int x = getMaxSum(D,n,i+1,j);
        int y = getMaxSum(D,n,i+1,j+1);
        maxSum[i][j] = Math.max(x, y) + D[i][j];
        return maxSum[i][j];
    }


    public static int minPathSum(int[][] grid) {
        if(grid==null || grid.length==0 || grid[0].length==0)
            return 0;
        int[][] dp = new int[grid.length][grid[0].length];
        dp[0][0] = grid[0][0];
        for (int i = 1; i < grid.length; i++) {
            dp[i][0] = dp[i-1][0] + grid[i][0];
        }
        for (int j = 1; j < grid[0].length; j++) {
            dp[0][j] = dp[0][j-1] + grid[0][j];
        }
        for (int i = 1; i < grid.length; i++) {
            for (int j = 1; j < grid[0].length; j++) {
                dp[i][j] = Math.min(dp[i-1][j],dp[i][j-1]) + grid[i][j];
            }
        }

        return dp[grid.length-1][grid[0].length-1];
    }
}
