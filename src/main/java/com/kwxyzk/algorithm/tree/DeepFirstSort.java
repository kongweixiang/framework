/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.algorithm.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;

/**
 * 深度优先遍历
 * @author kongweixiang
 * @date 2021/2/1
 * @since 1.0.0
 */
public class DeepFirstSort {

    /**
     * 使用递归
     * @param treeNode
     */
    public static void dfs(TreeNode treeNode) {
        if (treeNode == null) {
            return;
        }
        // 遍历节点
        System.out.print(treeNode.data);
        // 遍历左节点
        dfs(treeNode.leftNode);
        // 遍历右节点
        dfs(treeNode.rightNode);
    }

    /**
     * 使用栈
     * @param treeNode
     */
    public static void dfs2(TreeNode treeNode) {
        Stack<TreeNode> stack = new Stack<>();
        stack.push(treeNode);
        while (!stack.isEmpty()){
            TreeNode root = stack.pop();
            System.out.print(root.data);
            if (root.rightNode != null) {
                stack.push(root.rightNode);
            }
            if (root.leftNode != null) {
                stack.push(root.leftNode);
            }
        }
    }


    public static void main(String[] args) {
        TreeNode treeNode = TreeNode.createTreeNode();
        DeepFirstSort.dfs(treeNode);
        System.out.println();
        DeepFirstSort.dfs2(treeNode);
        System.out.println();
        BroadFirstSearch.bfs(treeNode);
        ArrayList<Integer> result = new ArrayList();

    }
}
