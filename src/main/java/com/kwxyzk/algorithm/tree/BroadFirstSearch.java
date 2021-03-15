/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.algorithm.tree;

import java.util.LinkedList;
import java.util.Queue;

/**
 * 广度优先
 * @author kongweixiang
 * @date 2021/2/1
 * @since 1.0.0
 */
public class BroadFirstSearch {

    public static void bfs(TreeNode treeNode){
        Queue<TreeNode> queue = new LinkedList<>();
        queue.add(treeNode);
        while (!queue.isEmpty()) {
            TreeNode root = queue.poll();
            System.out.print(root.data);
            if (root.leftNode != null) {
                queue.add(root.leftNode);
            }
            if (root.rightNode != null) {
                queue.add(root.rightNode);
            }
        }
        char[][] chars = new char[3][2];
        chars[0] = new char[]{'a','a'};
        chars[1] = new char[]{'a','a'};
        chars[2] = new char[]{'a','a'};

    }
}
