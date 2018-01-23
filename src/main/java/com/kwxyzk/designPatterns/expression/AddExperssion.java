package com.kwxyzk.designPatterns.expression;

import java.util.HashMap;

public class AddExperssion extends SymbolExpression {

    public AddExperssion(Expression _left, Expression _right) {
        super(_left, _right);
    }

    public int interpreter(HashMap<String, Integer> var) {
        return super._left.interpreter(var) + super._right.interpreter(var);
    }

}
