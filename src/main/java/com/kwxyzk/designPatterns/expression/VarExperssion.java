package com.kwxyzk.designPatterns.expression;

import java.util.HashMap;

public class VarExperssion extends Expression {
    private String _key;

    public VarExperssion(String _key) {
        this._key = _key;
    }
    public int interpreter(HashMap<String, Integer> var) {
        return var.get(this._key);
    }
}
