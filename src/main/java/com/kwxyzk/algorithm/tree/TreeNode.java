/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.algorithm.tree;

/**
 * @author kongweixiang
 * @date 2021/2/1
 * @since 1.0.0
 */

/**
 * 二叉树数据结构
 *
 *
 */
public class TreeNode {
    int data;
    TreeNode leftNode;
    TreeNode rightNode;
    public TreeNode() {

    }
    public TreeNode(int d) {
        data=d;
    }

    public TreeNode(TreeNode left,TreeNode right,int d) {
        leftNode=left;
        rightNode=right;
        data=d;
    }

    /**
     *          1
     *        /   \
     *      2      3
     *    /  \    / \
     *   4    5  6   7
     *
     * @return
     */
    public static TreeNode createTreeNode(){
        TreeNode head=new TreeNode(1);
        TreeNode second=new TreeNode(2);
        TreeNode three=new TreeNode(3);
        TreeNode four=new TreeNode(4);
        TreeNode five=new TreeNode(5);
        TreeNode six=new TreeNode(6);
        TreeNode seven=new TreeNode(7);
        head.rightNode=three;
        head.leftNode=second;
        second.rightNode=five;
        second.leftNode=four;
        three.rightNode=seven;
        three.leftNode=six;
        return head;
    }


}