package com.kwxyzk.designPatterns.expression;

public abstract class SymbolExpression extends Expression {
    protected Expression _left;
    protected Expression _right;

    public SymbolExpression(Expression _left, Expression _right) {
        this._left = _left;
        this._right = _right;
    }
}
