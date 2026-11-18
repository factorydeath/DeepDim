package com.deepdim;

/** Правило дополнительного затемнения для конкретного приложения. */
public final class AppRule {
    public final String pkg;
    public int boost;
    public int warm;

    public AppRule(String pkg, int boost, int warm) {
        this.pkg = pkg;
        this.boost = boost;
        this.warm = warm;
    }
}
